package com.zgq.zgqaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.ai.concurrency.AiConcurrencyLimiter;
import com.zgq.zgqaicodemother.ai.memory.ConversationMemoryCache;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryGuard;
import com.zgq.zgqaicodemother.cache.GoodAppListCache;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.constant.ChatHistoryConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.exception.ThrowUtils;
import com.zgq.zgqaicodemother.mapper.AppMapper;
import com.zgq.zgqaicodemother.model.dto.app.AppAddRequest;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.preview.PreviewService;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import com.zgq.zgqaicodemother.model.vo.UserVO;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import com.zgq.zgqaicodemother.service.ScreenshotService;
import com.zgq.zgqaicodemother.service.UserService;
import com.zgq.zgqaicodemother.utils.CodeDownloadUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用服务层实现，集中处理参数校验、查询条件和对外视图转换。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    /**
     * 排序字段只能使用实体中实际存在的列。
     * SQL 参数绑定保护的是查询值，不能把客户端输入的列名当作安全参数，因此列名必须单独校验。
     */
    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "appName", "cover", "initPrompt", "codeGenType", "deployKey",
            "deployedTime", "priority", "userId", "editTime", "createTime", "updateTime", "isDelete");

    /**
     * 路由模型超时、不可用或返回非法值时的兜底类型。
     *
     * <p>应用创建不能因为一次分类失败而失败；HTML 模式输出成本最低，且所有模型都支持，
     * 因此作为最后的降级方案。</p>
     */
    static final CodeGenTypeEnum FALLBACK_CODE_GEN_TYPE = CodeGenTypeEnum.HTML;

    @Resource
    private UserService userService;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Resource
    private ConversationMemoryCache conversationMemoryCache;

    @Resource
    private ScreenshotService screenshotService;

    /**
     * AI 生成并发控制器：限制全局并发，并保证同一应用同时只有一轮生成。
     */
    @Resource
    private AiConcurrencyLimiter aiConcurrencyLimiter;

    /**
     * 主页精选列表缓存：读多写少，用版本号失效避免扫库。
     */
    @Resource
    private GoodAppListCache goodAppListCache;

    /**
     * 预览状态服务：生成完成或构建结束后推进版本号，前端据此刷新 iframe。
     */
    @Resource
    private PreviewService previewService;

    /**
     * 重试护轨：智能路由属于幂等且低成本的模型调用，允许对瞬时故障做有界重试。
     */
    @Resource
    private AiRetryGuard aiRetryGuard;

    /** 部署静态站点的对外访问域名，由 APP_DEPLOY_HOST 环境变量配置。 */
    @Value("${app.deploy.host}")
    private String deployHost;

    /**
     * 根据提示词创建应用。生成类型由 AI 智能路由选择；模型不可用时回退到 HTML 模式，
     * 并返回保存后回填的应用 id。
     */
    @Override
    public long addApp(AppAddRequest appAddRequest, User loginUser) {
        // 在数据库写入前校验请求和登录身份，避免无效请求产生应用记录。
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR);
        // 新建实体并只接收提示词，避免把客户端输入批量复制到创建人、优先级等受保护属性上。
        App app = new App();
        app.setInitPrompt(appAddRequest.getInitPrompt());
        validApp(app, true);
        // 先验证原始输入长度，再去掉首尾空白；名称和保存的提示词采用同一份整理后的文本。
        String initPrompt = app.getInitPrompt().trim();
        app.setInitPrompt(initPrompt);
        // 按 Unicode 码点截取默认名称，避免截断由两个 UTF-16 字符组成的补充字符（如部分 emoji）。
        int nameLength = Math.min(initPrompt.codePointCount(0, initPrompt.length()),
                AppConstant.DEFAULT_APP_NAME_LENGTH);
        app.setAppName(initPrompt.substring(0, initPrompt.offsetByCodePoints(0, nameLength)));
        // 创建人取自可信的登录信息，普通优先级在此统一设置。
        app.setUserId(loginUser.getId());
        app.setPriority(AppConstant.DEFAULT_PRIORITY);
        // 路由只做一次轻量分类；生成类型决定后续使用哪套代码生成流程和模型。
        CodeGenTypeEnum selectedCodeGenType = routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        boolean result = this.save(app);
        // 保存失败不能返回成功 id；保存成功时 MyBatis-Flex 会把实体配置的雪花主键回填到 app。
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建应用失败");
        // 新应用会改变“我的应用”，精选列表也可能因为分页总数变化而需要重新统计，直接让缓存失效。
        invalidateAppListCache();
        log.info("应用创建成功，appId={}，生成类型={}，提示词长度={}",
                app.getId(), selectedCodeGenType.getValue(), initPrompt.length());
        return app.getId();
    }

    /**
     * 调用 AI 路由选择生成类型。
     *
     * <p>路由只是应用的优化项，不是创建应用的硬依赖：模型超时、限流或输出无法解析时记录告警，
     * 使用兜底类型继续创建，避免用户看到一个空白的应用列表。日志只记录提示词长度，避免泄露用户输入。</p>
     *
     * @param initPrompt 已去除首尾空白的用户需求
     * @return 有效的生成类型，绝不返回 null
     */
    private CodeGenTypeEnum routeCodeGenType(String initPrompt) {
        try {
            // 路由是幂等的轻量分类调用：超时、限流等瞬时故障值得重试一次，避免无谓地回退到 HTML。
            CodeGenTypeEnum selected = aiRetryGuard.call("code-gen-routing",
                    () -> aiCodeGenTypeRoutingService.routeCodeGenType(initPrompt));
            if (selected != null) {
                return selected;
            }
            log.warn("智能路由未返回生成类型，回退到 {} 模式，提示词长度={}",
                    FALLBACK_CODE_GEN_TYPE.getValue(), initPrompt.length());
        } catch (RuntimeException e) {
            // 超时和模型故障在生产环境可能反复出现，warn 只记录可读原因，堆栈放入 debug 避免刷屏。
            log.warn("智能路由失败，回退到 {} 模式，提示词长度={}，原因={}",
                    FALLBACK_CODE_GEN_TYPE.getValue(), initPrompt.length(), e.getMessage());
            log.debug("智能路由失败详情", e);
        }
        return FALLBACK_CODE_GEN_TYPE;
    }

    /**
     * add 为 true 表示新增，必须提供提示词；更新时只校验传入的字段，支持局部更新。
     * 本方法负责字段合法性，更新权限和允许修改的字段范围由调用方控制。
     */
    @Override
    public void validApp(App app, boolean add) {
        ThrowUtils.throwIf(app == null, ErrorCode.PARAMS_ERROR, "应用参数为空");
        String initPrompt = app.getInitPrompt();
        // 更新时 null 表示不修改提示词，显式传入空白则无论新增还是更新都应拒绝。
        if (add || initPrompt != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化提示词不能为空");
            ThrowUtils.throwIf(initPrompt.length() > AppConstant.MAX_INIT_PROMPT_LENGTH,
                    ErrorCode.PARAMS_ERROR, "初始化提示词过长");
        }
        String appName = app.getAppName();
        // 名称可以不传，但传入时必须有实际内容；长度限制同时约束提示词、名称和封面地址。
        if (appName != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(appName), ErrorCode.PARAMS_ERROR, "应用名称不能为空");
            ThrowUtils.throwIf(appName.length() > AppConstant.MAX_APP_NAME_LENGTH,
                    ErrorCode.PARAMS_ERROR, "应用名称过长");
        }
        String cover = app.getCover();
        // 封面为 null 表示本次不修改，空字符串允许管理员清空封面，因此这里只限制长度。
        ThrowUtils.throwIf(cover != null && cover.length() > AppConstant.MAX_COVER_LENGTH,
                ErrorCode.PARAMS_ERROR, "应用封面地址过长");
    }

    /**
     * 构造可复用的查询条件，不在此决定当前用户能查看哪些应用。
     * 调用方应先限制用户可传入的条件，再补充本人、精选等业务范围。
     */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        Integer isDelete = appQueryRequest.getIsDelete();
        ThrowUtils.throwIf(isDelete != null && isDelete != 0 && isDelete != 1,
                ErrorCode.PARAMS_ERROR, "删除标记必须为 0 或 1");
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        // 未指定排序时优先展示最新创建的应用；显式传入非法字段或方向直接拒绝。
        if (StrUtil.isBlank(sortField)) {
            sortField = "createTime";
        }
        if (StrUtil.isBlank(sortOrder)) {
            sortOrder = "descend";
        }
        ThrowUtils.throwIf(!SORT_FIELDS.contains(sortField), ErrorCode.PARAMS_ERROR, "排序字段不合法");
        ThrowUtils.throwIf(!"ascend".equals(sortOrder) && !"descend".equals(sortOrder),
                ErrorCode.PARAMS_ERROR, "排序顺序不合法");
        // 名称和提示词采用模糊匹配，空白搜索词不参与过滤。
        // 封面、生成类型和部署标识采用精确匹配：null 表示未筛选，空字符串仍是有效筛选值。
        // 数值条件只忽略 null，保留 priority=0、isDelete=0 等有效值。
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", appQueryRequest.getId())
                .like("appName", appQueryRequest.getAppName(), StrUtil.isNotBlank(appQueryRequest.getAppName()))
                .eq("cover", appQueryRequest.getCover(), appQueryRequest.getCover() != null)
                .like("initPrompt", appQueryRequest.getInitPrompt(), StrUtil.isNotBlank(appQueryRequest.getInitPrompt()))
                .eq("codeGenType", appQueryRequest.getCodeGenType(), appQueryRequest.getCodeGenType() != null)
                .eq("deployKey", appQueryRequest.getDeployKey(), appQueryRequest.getDeployKey() != null)
                .eq("priority", appQueryRequest.getPriority())
                .eq("userId", appQueryRequest.getUserId())
                .eq("isDelete", isDelete)
                .orderBy(sortField, "ascend".equals(sortOrder));
        // 时间、优先级等字段可能相同，追加唯一主键作为次级排序，使同值记录的分页顺序确定。
        // 已按主键排序时不重复追加；数据被并发修改时，普通偏移分页仍可能受影响。
        if (!"id".equals(sortField)) {
            queryWrapper.orderBy("id", false);
        }
        return queryWrapper;
    }

    /**
     * 单条详情补充创建人视图，复用 UserService 的脱敏规则。
     */
    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        fillCodeGenTypeText(app.getCodeGenType(), appVO);
        if (app.getUserId() != null) {
            // 创建人已删除或不存在时，getUserVO(null) 返回 null，应用详情仍可正常展示。
            appVO.setUser(userService.getUserVO(userService.getById(app.getUserId())));
        }
        return appVO;
    }

    /**
     * 把数据库中的枚举 value 转换为展示文案，前端无需重复维护类型名称。
     * 历史数据中的未知类型保持文案为 null，由前端使用兜底展示。
     */
    private static void fillCodeGenTypeText(String codeGenType, AppVO appVO) {
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum != null) {
            appVO.setCodeGenTypeText(codeGenTypeEnum.getText());
        }
    }

    /**
     * 批量转换应用视图，避免列表中每个应用分别查询一次创建人。
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 过滤缺失记录和创建人 id，并去重；同一用户的多个应用只需查询一次用户数据。
        Set<Long> userIds = appList.stream()
                .filter(Objects::nonNull)
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = new HashMap<>();
        if (CollUtil.isNotEmpty(userIds)) {
            // 一次批量查询后以 id 建立索引，避免 N+1 查询，并统一通过 UserService 脱敏。
            List<User> users = userService.listByIds(userIds);
            if (CollUtil.isNotEmpty(users)) {
                for (User user : users) {
                    userVOMap.put(user.getId(), userService.getUserVO(user));
                }
            }
        }
        // 按原应用列表逐条转换，保持数据库分页结果顺序，而不是使用批量用户查询的返回顺序。
        return appList.stream().map(app -> {
            if (app == null) {
                return null;
            }
            AppVO appVO = new AppVO();
            BeanUtil.copyProperties(app, appVO);
            fillCodeGenTypeText(app.getCodeGenType(), appVO);
            // 未查到创建人时索引返回 null，保留应用记录，避免历史应用因用户缺失而无法展示。
            appVO.setUser(userVOMap.get(app.getUserId()));
            return appVO;
        }).collect(Collectors.toList());
    }

    /**
     * 仅替换分页记录的展示类型，保留页码、每页数量、总记录数和总页数。
     */
    @Override
    public Page<AppVO> getAppVOPage(Page<App> appPage) {
        ThrowUtils.throwIf(appPage == null, ErrorCode.PARAMS_ERROR, "分页参数为空");
        Page<AppVO> appVOPage = new Page<>(appPage.getPageNumber(), appPage.getPageSize(), appPage.getTotalRow());
        appVOPage.setTotalPage(appPage.getTotalPage());
        appVOPage.setRecords(getAppVOList(appPage.getRecords()));
        return appVOPage;
    }

    /**
     * 执行分页查询。此处只要求页码和数量为正数，管理员可使用超过用户上限的每页数量。
     * 调用方负责鉴权、用户每页数量上限，并防止普通用户注入 isDelete 等管理端条件。
     */
    @Override
    public Page<App> listAppByPage(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        ThrowUtils.throwIf(appQueryRequest.getPageNum() < 1 || appQueryRequest.getPageSize() < 1,
                ErrorCode.PARAMS_ERROR, "页码和每页数量必须大于 0");
        QueryWrapper queryWrapper = getQueryWrapper(appQueryRequest);
        Page<App> appPage = new Page<>(appQueryRequest.getPageNum(), appQueryRequest.getPageSize());
        if (appQueryRequest.getIsDelete() != null) {
            // 指定删除标记时，仅在本次只读回调内关闭默认逻辑删除过滤，避免与 isDelete=1 冲突。
            // 查询条件仍保留显式标记，因此值为 1 时只查询已删除记录，值为 0 时只查询未删除记录。
            // MyBatis-Flex 在回调结束时通过 finally 恢复线程标记，查询抛异常也不会影响后续请求。
            return LogicDeleteManager.execWithoutLogicDelete(() -> this.page(appPage, queryWrapper));
        }
        // 未显式筛选删除标记时保留框架默认行为，只查询未逻辑删除的数据。
        return this.page(appPage, queryWrapper);
    }

    /**
     * 分页查询精选应用，并在命中缓存时直接返回，避免每次打开首页都查询数据库和创建人。
     *
     * <p>缓存只保存脱敏后的 {@link AppVO}，不保存用户实体；未命中或缓存不可用时回源数据库，
     * 再把结果写回缓存。缓存失效通过版本号完成，发布新版本后旧 key 会按 TTL 自然过期。</p>
     */
    @Override
    public Page<AppVO> listGoodAppVOPage(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        Optional<Page<AppVO>> cached = goodAppListCache.find(appQueryRequest);
        if (cached.isPresent()) {
            return cached.get();
        }
        Page<AppVO> appVOPage = getAppVOPage(listAppByPage(appQueryRequest));
        goodAppListCache.put(appQueryRequest, appVOPage);
        return appVOPage;
    }

    /**
     * 让精选列表缓存立即失效。
     *
     * <p>应用名称、封面、优先级、部署信息都会出现在精选列表中，任何写入都需要调用本方法；
     * 服务内部写路径（创建、删除、截图回填封面）已经自动调用，控制器只需要在直接更新实体后调用。</p>
     */
    @Override
    public void invalidateAppListCache() {
        try {
            goodAppListCache.invalidate();
        } catch (RuntimeException e) {
            // 缓存失效失败不能阻断业务写入；最坏情况是列表在 TTL 内展示旧数据。
            log.warn("精选应用列表缓存失效失败，将在 TTL 后自然过期，原因={}", e.getMessage());
        }
    }

    /**
     * 在同一事务内逻辑删除应用及其全部对话历史。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeAppWithChatHistory(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 无效");
        // 先清理关联历史，再逻辑删除应用；事务保证其中一步失败时一并回滚。
        chatHistoryService.removeByAppId(appId);
        boolean removed = this.removeById(appId);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "删除应用失败");
        // 应用删除后清理本地缓存和 Redis 记忆，避免残留上下文被后续同 id 数据误用。
        conversationMemoryCache.evict(appId);
        // 预览状态也要一起清掉，否则新应用（同 id 复用）会读到旧的构建状态。
        previewService.evict(appId);
        invalidateAppListCache();
        return true;
    }

    /**
     * 校验应用归属后返回流式生成结果，并在订阅、完成或失败时写入对话历史。
     */
    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        // 5. 调用 AI 生成代码
        // Flux.defer 让每次实际订阅对应一轮对话：先申请并发许可，再恢复对话记忆、保存用户消息，
        // 最后调用模型并保存最终 AI 回复。许可在流结束、取消或异常时通过 doFinally 归还。
        return Flux.defer(() -> {
            AiConcurrencyLimiter.Lease lease = aiConcurrencyLimiter.acquire(appId);
            Flux<String> stream;
            try {
                stream = doChatToGenCode(appId, message, loginUser, codeGenTypeEnum);
            } catch (RuntimeException error) {
                // 订阅阶段就失败（例如记忆初始化抛错）时不会触发 doFinally，需要手动归还许可。
                lease.close();
                throw error;
            }
            return stream.doFinally(signal -> lease.close());
        });
    }

    /**
     * 执行一轮真实的 AI 生成：恢复记忆、记录用户消息、生成并落库 AI 回复。
     *
     * <p>拆分出来是为了让并发许可的获取与释放在外层成对出现，内层只关心生成流程本身。</p>
     */
    private Flux<String> doChatToGenCode(Long appId, String message, User loginUser,
                                         CodeGenTypeEnum codeGenTypeEnum) {
        // 先初始化对话记忆：首次会从数据库回源历史，此时本轮用户消息尚未落库，避免重复加入上下文。
        conversationMemoryCache.get(appId);
        chatHistoryService.saveMessage(appId, loginUser.getId(), ChatHistoryMessageTypeEnum.USER, message);
        StringBuilder responseBuilder = new StringBuilder();
        Flux<String> codeStream;
        try {
            codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
        } catch (RuntimeException error) {
            // 同步创建流失败时仍保存失败消息，保证用户消息有对应的 AI 记录。
            saveAiFailureMessage(appId, loginUser.getId(), error);
            return Flux.error(error);
        }
        return codeStream
                .doOnNext(responseBuilder::append)
                .doOnComplete(() -> {
                    String response = responseBuilder.toString();
                    if (StrUtil.isNotBlank(response)) {
                        chatHistoryService.saveMessage(appId, loginUser.getId(),
                                ChatHistoryMessageTypeEnum.AI, response);
                    }
                })
                .doOnError(error -> saveAiFailureMessage(appId, loginUser.getId(), error));
    }

    /**
     * 保存 AI 回复失败记录；保存失败时只记录日志，保留原始生成异常。
     */
    private void saveAiFailureMessage(Long appId, Long userId, Throwable error) {
        String detail = StrUtil.isNotBlank(error.getMessage())
                ? error.getMessage() : error.getClass().getSimpleName();
        try {
            chatHistoryService.saveMessage(appId, userId, ChatHistoryMessageTypeEnum.AI,
                    ChatHistoryConstant.AI_FAILURE_MESSAGE_PREFIX + detail);
        } catch (Exception saveError) {
            // 保存错误记录失败时保留原始生成异常，避免覆盖真正的失败原因。
            log.error("保存 AI 回复失败记录失败，appId={}", appId, saveError);
        }
        // 失败轮次不写入对话记忆；清理本地/Redis 缓存，让下一轮从数据库历史重新构建上下文。
        conversationMemoryCache.evict(appId);
    }

    /**
     * 复制已生成的代码到部署目录，更新部署标识和时间并拼出访问地址。
     */
    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限部署该应用，仅本人可以部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 检查是否已有 deployKey
        String deployKey = app.getDeployKey();
        // 没有则生成 6 位 deployKey（大小写字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，构建工程目录路径
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        String projectDirName = codeGenType.getValue() + "_" + appId;
        File projectDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
        // 6. 检查工程目录是否存在
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
        // 7. 确定可部署目录：Vue 源码里的 index.html 是 Vite 开发入口（/src/main.js），
        // 浏览器无法直接执行 .vue 与裸模块导入，必须部署构建后的 dist；HTML/多文件模式直接部署源码。
        File sourceDir = projectDir;
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            sourceDir = new File(projectDir, "dist");
            if (!sourceDir.isDirectory() || !new File(sourceDir, "index.html").isFile()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "Vue 工程尚未构建完成，请等待预览可访问后再部署");
            }
        }
        // 8. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        // 9. 更新应用的 deployKey 和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 部署时间会展示在精选列表里，写成功后立即让列表缓存失效。
        invalidateAppListCache();
        // 10. 返回可访问的 URL
        String deployUrl = String.format("%s/%s/", deployHost.trim().replaceAll("/+$", ""), deployKey);
        // 11. 部署成功后异步生成截图并更新封面，截图失败不影响部署结果
        generateAppScreenshotAsync(appId, deployUrl);
        return deployUrl;
    }

    /**
     * 校验下载权限后，把生成目录中的源码过滤并打包为 ZIP 写入响应。
     *
     * <p>与部署流程不同，这里读取 {@link AppConstant#CODE_OUTPUT_ROOT_DIR} 而不是部署目录：
     * 部署目录是打包后的静态产物，用户需要的是可继续开发和修改的源代码。响应头只在所有校验
     * 和文件收集完成后设置，避免参数错误时返回 ZIP 响应头却输出 JSON 错误体。</p>
     */
    @Override
    public void downloadAppCode(Long appId, User loginUser, HttpServletResponse response) {
        // 1. 基础校验：应用 id、登录用户和响应对象都必须有效
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        ThrowUtils.throwIf(response == null, ErrorCode.SYSTEM_ERROR, "响应对象不能为空");
        // 2. 应用必须存在；下载能力只对已生成代码的应用开放
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 仅应用创建者可以下载源代码，管理员也不能绕过该限制
        if (!loginUser.getId().equals(app.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        // 4. 使用枚举白名单拼接目录名，避免数据库中异常的类型值影响文件路径
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        String sourceDirName = codeGenType.getValue() + "_" + appId;
        Path sourceRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, sourceDirName)
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        }
        // 5. 收集需要下载的文件；node_modules、dist 等会在过滤阶段排除
        List<Path> files;
        try {
            files = CodeDownloadUtils.collectDownloadableFiles(sourceRoot);
        } catch (IOException e) {
            log.error("读取应用生成目录失败，appId={}", appId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取应用代码失败");
        }
        if (files.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用代码目录中没有可下载的源码文件");
        }
        // 6. 校验全部通过后再设置响应头，浏览器据此把响应识别为附件并使用给定文件名
        String downloadFileName = CodeDownloadUtils.buildDownloadFileName(app.getAppName(), appId);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(downloadFileName, StandardCharsets.UTF_8)
                .build()
                .toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 7. 流式写入 ZIP，不在服务器上保留临时压缩包
        log.info("开始下载应用代码，appId={}, userId={}, fileCount={}",
                appId, loginUser.getId(), files.size());
        try {
            CodeDownloadUtils.writeZip(sourceRoot, files, response.getOutputStream());
        } catch (IOException e) {
            log.error("下载应用代码失败，appId={}", appId, e);
            if (!response.isCommitted()) {
                // 响应尚未提交时清掉 ZIP 响应头，让全局异常处理器返回 JSON 错误。
                response.reset();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载代码失败");
            }
            // 已经写出部分 ZIP 时无法再改写响应体，中断本次响应让客户端感知下载失败。
            log.warn("ZIP 已开始传输，无法回滚响应，appId={}", appId);
        }
    }

    /**
     * 在虚拟线程中触发封面截图生成，避免阻塞部署接口。
     *
     * <p>截图包含浏览器启动、页面加载和对象存储上传，耗时明显高于普通的数据库更新。部署结果不应
     * 依赖截图是否成功，因此任务失败时只记录日志，部署接口仍然返回可访问地址。</p>
     */
    private void generateAppScreenshotAsync(Long appId, String deployUrl) {
        try {
            Thread.ofVirtual()
                    .name("app-screenshot-" + appId)
                    .start(() -> generateAppScreenshot(appId, deployUrl));
        } catch (Exception e) {
            // 虚拟线程创建失败不能影响已经完成的部署结果。
            log.error("启动应用封面截图任务失败，appId={}", appId, e);
        }
    }

    /**
     * 生成截图、上传对象存储并更新应用封面。
     *
     * <p>独立成包级方法便于测试截图成功和失败两条路径；所有异常在此兜底，不向调用方抛出。</p>
     */
    void generateAppScreenshot(Long appId, String deployUrl) {
        try {
            String coverUrl = screenshotService.generateAndUploadScreenshot(deployUrl);
            if (StrUtil.isBlank(coverUrl)) {
                log.warn("生成应用封面失败，截图服务未返回图片地址，appId={}", appId);
                return;
            }
            App coverUpdate = new App();
            coverUpdate.setId(appId);
            coverUpdate.setCover(coverUrl);
            boolean updated = this.updateById(coverUpdate);
            if (!updated) {
                log.warn("更新应用封面失败，应用可能已被删除，appId={}", appId);
                return;
            }
            log.info("应用封面更新成功，appId={}，cover={}", appId, coverUrl);
            // 封面是精选列表的展示字段，回填成功后让列表缓存失效。
            invalidateAppListCache();
        } catch (Exception e) {
            // 截图或上传失败不回滚部署，仅保留可访问的部署结果。
            log.error("部署后生成应用封面失败，appId={}，deployUrl={}", appId, deployUrl, e);
        }
    }

}
