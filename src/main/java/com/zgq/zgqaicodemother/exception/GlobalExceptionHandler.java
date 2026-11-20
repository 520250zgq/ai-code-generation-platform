package com.zgq.zgqaicodemother.exception;

import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.common.ResultUtils;
import com.zgq.zgqaicodemother.security.ratelimit.RateLimitExceededException;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一捕获业务异常和运行时异常，转换为前端可识别的 BaseResponse。
 */
@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 返回业务异常携带的错误码和可读提示，不向客户端暴露堆栈。
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 限流拒绝：HTTP 状态固定返回 429，并带上 Retry-After，客户端和网关据此退避。
     *
     * <p>与其他业务异常不同，这里必须使用真实的 429 状态码：网关、CDN 和前端重试组件
     * 通常只根据 HTTP 状态判断是否限流，只看响应体里的业务码会被当成普通失败而立即重试，
     * 反而加剧流量冲击。</p>
     *
     * @param e 限流异常
     * @return 429 响应
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<BaseResponse<?>> rateLimitExceededHandler(RateLimitExceededException e) {
        long retryAfter = Math.max(1L, e.getRetryAfterSeconds());
        log.warn("请求被限流，规则={}，维度={}，建议 {} 秒后重试", e.getRule(), e.getDimension(), retryAfter);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(ResultUtils.error(ErrorCode.TOO_MANY_REQUESTS.getCode(), e.getMessage()));
    }

    /**
     * 请求体不是合法 JSON 时按参数错误返回，而不是落到兜底的“系统错误”。
     *
     * <p>客户端拼错 JSON、用错编码或少了引号都属于调用方问题，返回 40000 可以让前端直接提示
     * “请求格式错误”，也避免这类高频无效请求在日志里被记成服务端异常。</p>
     *
     * @param e 请求体解析异常
     * @return 参数错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> messageNotReadableHandler(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败，已按参数错误返回：{}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求体格式错误，请检查 JSON 是否完整且为 UTF-8 编码");
    }

    /**
     * Redis 是登录态和对话记忆的必需依赖；连接失败时返回可操作的提示，
     * 避免用户只看到笼统的“系统错误”。
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public BaseResponse<?> redisConnectionFailureHandler(RedisConnectionFailureException e) {
        log.error("Redis 连接失败，请确认 Redis 已启动并检查 spring.data.redis 配置", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR,
                "Redis 连接失败，请确认 Redis 已启动（默认 localhost:6379）");
    }

    /**
     * 兜底处理未预期异常，避免原始异常信息直接返回给客户端。
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
