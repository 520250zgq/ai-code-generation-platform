package com.zgq.zgqaicodemother.security.ratelimit;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制器接口限流切面。
 *
 * <p>切面只处理 {@code com.zgq.zgqaicodemother.controller} 包下的 public 方法，并先读取方法上的
 * {@link RateLimit} 注解：没有注解时立即放行，不访问请求上下文、登录用户或 Redis，避免给普通
 * 查询接口增加无意义的开销。</p>
 *
 * <p>优先级设置为 {@link Ordered#HIGHEST_PRECEDENCE} 之后一百，必须早于 {@code AuthInterceptor}
 * （后者没有显式排序，默认最低优先级）。这样未登录或超大流量会先在限流层被挡下，而不是先做
 * Session/数据库鉴权再消耗 AI 资源。切面本身不是鉴权组件：用户未登录时只按 IP 限流，登录用户
 * 则可以同时拥有用户和 IP 两个维度的额度。</p>
 *
 * <p>日志只记录规则名、维度、userId/IP，以及内部异常类型，不打印对话内容、请求参数或异常
 * 堆栈正文；堆栈放在 debug 级别。限流器或请求上下文出现意外异常时，本切面会跳过限流并继续
 * 执行业务，保证限流组件不会成为 AI 对话的故障点。</p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitAspect {

    private static final String DEFAULT_MESSAGE = "请求过于频繁，请稍后重试";

    private static final String USER_DIMENSION = "user";

    private static final String IP_DIMENSION = "ip";

    private static final String UNKNOWN_KEY = "unknown";

    /**
     * 规则名和标识片段的长度上限。规则名来自代码常量，正常不会接近上限；截断用于防止
     * 误配置产生超长 Redis key。
     */
    private static final int MAX_RULE_KEY_LENGTH = 64;

    private static final int MAX_IDENTIFIER_LENGTH = 64;

    private final RateLimitProperties properties;

    private final RateLimiter rateLimiter;

    private final UserService userService;

    public RateLimitAspect(RateLimitProperties properties,
                           RateLimiter rateLimiter,
                           UserService userService) {
        this.properties = properties == null ? new RateLimitProperties() : properties;
        this.rateLimiter = rateLimiter;
        this.userService = userService;
    }

    /**
     * 对控制器方法执行限流。
     *
     * @param joinPoint 控制器方法切入点
     * @return 业务方法返回值
     * @throws Throwable 业务方法抛出的异常，以及命中限流时抛出的 {@link RateLimitExceededException}
     */
    @Around("execution(public * com.zgq.zgqaicodemother.controller..*.*(..))")
    public Object limitControllerMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!(joinPoint.getSignature() instanceof MethodSignature methodSignature)) {
            return joinPoint.proceed();
        }
        RateLimit[] rules = resolveRules(joinPoint, methodSignature.getMethod());
        // 无注解方法是绝大多数，必须在任何用户/请求解析之前返回，保持零额外开销。
        if (rules.length == 0) {
            return joinPoint.proceed();
        }
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            // 非 Web 请求上下文（例如内部直接调用代理对象）无法确定限流维度，放行而不是报错。
            log.debug("未获取到 HTTP 请求上下文，跳过接口限流，ruleCount={}", rules.length);
            return joinPoint.proceed();
        }
        try {
            applyRules(rules, request);
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            // 限流是保护能力，任何未预期异常都不应让正常业务请求失败；方法参数不进入日志。
            log.warn("限流组件异常，本次请求跳过限流，ruleCount={}，原因={}",
                    rules.length, e.getClass().getSimpleName());
            log.debug("限流组件异常详情", e);
        }
        return joinPoint.proceed();
    }

    /**
     * 逐条执行规则。同一规则可能同时生成用户和 IP 两个计数，任意一个超限都会立即拒绝。
     */
    private void applyRules(RateLimit[] rules, HttpServletRequest request) {
        Long userId = needsUserDimension(rules) ? resolveLoginUserId(request) : null;
        String ip = ClientIpResolver.resolve(request, properties.getTrustedProxyHeader());
        for (RateLimit rule : rules) {
            if (rule == null) {
                continue;
            }
            // 未登录请求没有用户维度，只能退化为 IP 维度；这是“先限流再鉴权”的关键路径。
            if (rule.byUser() && userId != null) {
                checkRule(rule, USER_DIMENSION, Long.toString(userId), userId, ip);
            }
            if (rule.byIp()) {
                checkRule(rule, IP_DIMENSION, ip, userId, ip);
            }
        }
    }

    private boolean needsUserDimension(RateLimit[] rules) {
        for (RateLimit rule : rules) {
            if (rule != null && rule.byUser()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行单条规则、单个维度的限流判断。
     */
    private void checkRule(RateLimit rule,
                           String dimension,
                           String identifier,
                           Long userId,
                           String ip) {
        String ruleKey = normalizeRuleKey(rule.key());
        String key = ruleKey + ":" + dimension + ":" + normalizeIdentifier(identifier);
        RateLimitDecision decision = rateLimiter.acquire(key, rule.permits(), rule.windowSeconds());
        if (decision == null || decision.allowed()) {
            return;
        }
        String message = rule.message();
        if (message == null || message.isBlank()) {
            message = DEFAULT_MESSAGE;
        }
        // 只输出限流定位信息，不记录请求体、对话内容或异常堆栈，避免隐私和日志膨胀。
        log.warn("接口触发限流，rule={}，dimension={}，userId={}，ip={}",
                ruleKey, dimension, userId, ip);
        throw new RateLimitExceededException(message, ruleKey, dimension,
                decision.retryAfterSeconds());
    }

    /**
     * 读取当前请求的登录用户 id。限流发生在鉴权之前，未登录是正常路径，不能把业务异常抛给
     * 客户端；数据库或 Session 异常同样按未登录处理，确保只影响用户维度，不影响 IP 限流。
     */
    private Long resolveLoginUserId(HttpServletRequest request) {
        try {
            User loginUser = userService.getLoginUser(request);
            return loginUser == null ? null : loginUser.getId();
        } catch (BusinessException e) {
            return null;
        } catch (RuntimeException e) {
            log.debug("读取登录用户失败，按未登录请求执行 IP 限流，原因={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 读取方法上的可重复注解，并兼容“接口方法声明、实现方法执行”的代理场景。
     *
     * <p>JDK 代理的签名方法可能来自接口，而 CGLIB 代理可能来自实现类。这里同时检查签名方法
     * 和 AOP 工具解析出的最具体实现方法，并按注解值去重，避免接口和实现重复声明时打两次
     * 相同的限流规则。</p>
     */
    private static RateLimit[] resolveRules(ProceedingJoinPoint joinPoint, Method signatureMethod) {
        RateLimit[] signatureRules = signatureMethod.getAnnotationsByType(RateLimit.class);
        Object target = joinPoint.getTarget();
        // 直接声明在目标类上的方法无需再解析最具体方法；无注解的普通接口在此立即返回，
        // 只有接口方法或继承方法才继续做一次反射兼容处理。
        if (target == null || signatureMethod.getDeclaringClass().equals(target.getClass())) {
            return signatureRules;
        }
        Method specificMethod;
        try {
            specificMethod = AopUtils.getMostSpecificMethod(signatureMethod, target.getClass());
        } catch (RuntimeException e) {
            return signatureRules;
        }
        if (specificMethod == null || specificMethod.equals(signatureMethod)) {
            return signatureRules;
        }
        RateLimit[] specificRules = specificMethod.getAnnotationsByType(RateLimit.class);
        if (specificRules.length == 0) {
            return signatureRules;
        }
        if (signatureRules.length == 0) {
            return specificRules;
        }
        List<RateLimit> merged = new ArrayList<>(signatureRules.length + specificRules.length);
        for (RateLimit rule : signatureRules) {
            merged.add(rule);
        }
        for (RateLimit rule : specificRules) {
            if (!merged.contains(rule)) {
                merged.add(rule);
            }
        }
        return merged.toArray(new RateLimit[0]);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    private static String normalizeRuleKey(String ruleKey) {
        if (ruleKey == null || ruleKey.isBlank()) {
            return UNKNOWN_KEY;
        }
        String normalized = ruleKey.trim();
        return normalized.length() > MAX_RULE_KEY_LENGTH
                ? normalized.substring(0, MAX_RULE_KEY_LENGTH)
                : normalized;
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return UNKNOWN_KEY;
        }
        String normalized = identifier.trim();
        return normalized.length() > MAX_IDENTIFIER_LENGTH
                ? normalized.substring(0, MAX_IDENTIFIER_LENGTH)
                : normalized;
    }
}
