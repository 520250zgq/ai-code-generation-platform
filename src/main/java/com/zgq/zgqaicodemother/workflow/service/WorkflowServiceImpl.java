package com.zgq.zgqaicodemother.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.exception.ThrowUtils;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.workflow.CodeGenWorkflow;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.WorkflowEvent;
import com.zgq.zgqaicodemother.workflow.model.WorkflowResult;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import com.zgq.zgqaicodemother.workflow.tools.ContentImageSearchTool;
import com.zgq.zgqaicodemother.workflow.tools.LogoGeneratorTool;
import com.zgq.zgqaicodemother.workflow.tools.MermaidDiagramTool;
import com.zgq.zgqaicodemother.workflow.tools.UndrawIllustrationTool;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 工作流服务实现。
 *
 * <p>服务层负责三件事：资源归属和参数校验、工作流并发控制、把内部上下文转换成稳定的对外结果。
 * 工作流本身在虚拟线程中执行，避免长时间占用 Web 请求线程；同步执行入口主要用于测试和后台
 * 任务，前端交互应使用 SSE。</p>
 */
@Slf4j
@Service
public class WorkflowServiceImpl implements WorkflowService {

    /**
     * 节点完成事件中错误信息的最大展示长度。
     */
    private static final int EVENT_ERROR_LENGTH = 240;

    private final CodeGenWorkflow codeGenWorkflow;

    private final AppService appService;

    private final WorkflowProperties properties;

    private final ContentImageSearchTool contentImageSearchTool;

    private final UndrawIllustrationTool undrawIllustrationTool;

    private final MermaidDiagramTool mermaidDiagramTool;

    private final LogoGeneratorTool logoGeneratorTool;

    private final CosManager cosManager;

    /**
     * 工作流并发许可。使用公平模式，避免高并发时个别请求长期排队。
     */
    private final Semaphore runPermits;

    private final int maxConcurrentRuns;

    public WorkflowServiceImpl(CodeGenWorkflow codeGenWorkflow,
                               AppService appService,
                               WorkflowProperties properties,
                               ContentImageSearchTool contentImageSearchTool,
                               UndrawIllustrationTool undrawIllustrationTool,
                               MermaidDiagramTool mermaidDiagramTool,
                               LogoGeneratorTool logoGeneratorTool,
                               CosManager cosManager) {
        this.codeGenWorkflow = codeGenWorkflow;
        this.appService = appService;
        this.properties = properties;
        this.contentImageSearchTool = contentImageSearchTool;
        this.undrawIllustrationTool = undrawIllustrationTool;
        this.mermaidDiagramTool = mermaidDiagramTool;
        this.logoGeneratorTool = logoGeneratorTool;
        this.cosManager = cosManager;
        this.maxConcurrentRuns = Math.max(1, properties.getMaxConcurrentRuns());
        this.runPermits = new Semaphore(maxConcurrentRuns, true);
    }

    @Override
    public WorkflowResult execute(Long appId, String prompt, User loginUser) {
        App app = resolveApp(appId, loginUser);
        WorkflowContext initialContext = newContext(app, prompt, loginUser);
        acquirePermit();
        WorkflowContext context = initialContext;
        try {
            try {
                context = codeGenWorkflow.execute(initialContext);
            } catch (Exception e) {
                // 图执行异常（非节点内部已处理的业务失败）在这里兜底，仍返回可读结果。
                log.error("工作流执行异常，workflowId={}，appId={}",
                        initialContext.getWorkflowId(), appId, e);
                context.fail("工作流执行失败", safeErrorMessage(e));
            }
            context.finish();
            syncAppCodeGenType(app, context);
            WorkflowResult result = toResult(context);
            log.info("工作流执行完成，workflowId={}，appId={}，success={}，耗时={}ms",
                    result.workflowId(), appId, result.success(), result.elapsedMs());
            return result;
        } finally {
            runPermits.release();
        }
    }

    @Override
    public Flux<WorkflowEvent> executeStream(Long appId, String prompt, User loginUser) {
        // 参数、应用归属和提示词在返回 Flux 前完成校验，失败时控制器仍能返回普通 JSON 错误。
        App app = resolveApp(appId, loginUser);
        WorkflowContext initialContext = newContext(app, prompt, loginUser);
        return Flux.create(sink -> {
            if (!runPermits.tryAcquire()) {
                WorkflowContext rejected = initialContext;
                rejected.fail("工作流未启动", "工作流并发已满，请稍后重试");
                rejected.finish();
                emitSafely(sink, WorkflowEvent.error(rejected.getWorkflowId(),
                        "工作流并发已满，请稍后重试", 0L, toResult(rejected)));
                sink.complete();
                return;
            }
            try {
                Thread.ofVirtual()
                        .name("ai-workflow-" + initialContext.getWorkflowId())
                        .start(() -> runStream(sink, initialContext, app));
            } catch (Exception e) {
                runPermits.release();
                log.error("启动工作流虚拟线程失败，workflowId={}", initialContext.getWorkflowId(), e);
                initialContext.fail("工作流未启动", "启动工作流执行线程失败");
                initialContext.finish();
                emitSafely(sink, WorkflowEvent.error(initialContext.getWorkflowId(),
                        "启动工作流执行线程失败", 0L, toResult(initialContext)));
                sink.complete();
            }
        });
    }

    /**
     * 在虚拟线程中执行图并推送节点事件。
     */
    private void runStream(FluxSink<WorkflowEvent> sink, WorkflowContext initialContext, App app) {
        WorkflowContext latest = initialContext;
        AtomicInteger step = new AtomicInteger();
        try {
            emitSafely(sink, WorkflowEvent.start(initialContext.getWorkflowId(),
                    "开始执行 AI 网站生成工作流"));
            for (NodeOutput<MessagesState<String>> output
                    : codeGenWorkflow.compiledGraph().stream(WorkflowState.initialInput(initialContext))) {
                if (sink.isCancelled()) {
                    log.info("工作流 SSE 已取消，停止推送后续节点，workflowId={}",
                            initialContext.getWorkflowId());
                    break;
                }
                if (output.isSTART() || output.isEND()) {
                    continue;
                }
                WorkflowContext current = WorkflowState.get(output);
                if (current == null) {
                    continue;
                }
                latest = current;
                int currentStep = step.incrementAndGet();
                String nodeName = WorkflowNodes.displayName(output.node());
                emitSafely(sink, WorkflowEvent.nodeCompleted(
                        current.getWorkflowId(), output.node(), nodeName, currentStep,
                        nodeMessage(output.node(), current), current.elapsedMillis()));
            }
            if (sink.isCancelled()) {
                latest.finish();
                return;
            }
            latest.finish();
            syncAppCodeGenType(app, latest);
            WorkflowResult result = toResult(latest);
            emitSafely(sink, WorkflowEvent.completed(latest.getWorkflowId(),
                    result.success() ? "AI 网站生成工作流执行完成" : "AI 网站生成工作流执行结束（存在失败）",
                    result.elapsedMs(), result));
            sink.complete();
        } catch (Throwable e) {
            log.error("工作流流式执行异常，workflowId={}", initialContext.getWorkflowId(), e);
            latest.fail("工作流执行失败", safeErrorMessage(e));
            latest.finish();
            syncAppCodeGenType(app, latest);
            if (!sink.isCancelled()) {
                emitSafely(sink, WorkflowEvent.error(latest.getWorkflowId(),
                        "工作流执行失败：" + safeErrorMessage(e), latest.elapsedMillis(), toResult(latest)));
                sink.complete();
            }
        } finally {
            runPermits.release();
        }
    }

    @Override
    public String mermaidGraph() {
        return codeGenWorkflow.mermaidGraph();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("maxConcurrentRuns", maxConcurrentRuns);
        status.put("activeRuns", Math.max(0, maxConcurrentRuns - runPermits.availablePermits()));
        Map<String, Object> contentImage = new LinkedHashMap<>();
        contentImage.put("provider", contentImageSearchTool.configuredProvider());
        contentImage.put("providers", contentImageSearchTool.describeAvailability());
        status.put("contentImage", contentImage);
        status.put("undraw", undrawIllustrationTool.available());
        status.put("mermaid", mermaidDiagramTool.available());
        status.put("logo", logoGeneratorTool.available());
        status.put("cos", cosManager.isAvailable());
        status.put("nodes", WorkflowNodes.ORDERED_NODES);
        return status;
    }

    /**
     * 校验应用存在且属于当前用户，返回应用实体。
     */
    private App resolveApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        ThrowUtils.throwIf(!properties.isEnabled(), ErrorCode.OPERATION_ERROR, "AI 工作流功能未启用");
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!loginUser.getId().equals(app.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限执行该应用的工作流");
        }
        return app;
    }

    /**
     * 构造初始上下文。提示词为空时回退到应用创建时的初始化提示词。
     */
    private WorkflowContext newContext(App app, String prompt, User loginUser) {
        String actualPrompt = StrUtil.isBlank(prompt) ? app.getInitPrompt() : prompt.trim();
        ThrowUtils.throwIf(StrUtil.isBlank(actualPrompt), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        ThrowUtils.throwIf(actualPrompt.length() > AppConstant.MAX_INIT_PROMPT_LENGTH,
                ErrorCode.PARAMS_ERROR, "提示词过长");
        return WorkflowContext.builder()
                .workflowId(UUID.randomUUID().toString())
                .appId(app.getId())
                .userId(loginUser.getId())
                .originalPrompt(actualPrompt)
                .currentStep("初始化")
                .startedAt(LocalDateTime.now())
                .build();
    }

    private void acquirePermit() {
        if (!runPermits.tryAcquire()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工作流并发已满，请稍后重试");
        }
    }

    /**
     * 工作流可能在路由阶段选择了与创建应用时不同的生成模式。代码生成成功后同步更新应用的
     * {@code codeGenType}，否则现有预览、部署和下载接口仍会按旧模式拼接目录，找不到新代码。
     *
     * <p>同步失败不改变工作流结果，只追加告警；代码已经生成，下一次生成或人工修正仍可恢复。</p>
     */
    private void syncAppCodeGenType(App app, WorkflowContext context) {
        CodeGenTypeEnum type = context.getGenerationType();
        if (app == null || type == null || StrUtil.isBlank(context.getGeneratedCodeDir())) {
            return;
        }
        if (type.getValue().equals(app.getCodeGenType())) {
            return;
        }
        App update = new App();
        update.setId(app.getId());
        update.setCodeGenType(type.getValue());
        update.setEditTime(LocalDateTime.now());
        try {
            boolean updated = appService.updateById(update);
            if (updated) {
                app.setCodeGenType(type.getValue());
                log.info("工作流更新应用生成类型成功，appId={}，codeGenType={}",
                        app.getId(), type.getValue());
            } else {
                context.addWarning("生成类型未能同步到应用记录，预览或部署前请重新生成");
                log.warn("工作流更新应用生成类型失败，appId={}，codeGenType={}",
                        app.getId(), type.getValue());
            }
        } catch (Exception e) {
            context.addWarning("生成类型同步异常，预览或部署前请重新生成");
            log.error("工作流同步应用生成类型异常，appId={}", app.getId(), e);
        }
    }

    /**
     * 节点完成事件的展示文案。只包含数量、类型等非敏感信息。
     */
    private static String nodeMessage(String node, WorkflowContext context) {
        if (context.hasError()) {
            return "执行失败：" + StrUtil.maxLength(context.getErrorMessage(), EVENT_ERROR_LENGTH);
        }
        return switch (node) {
            case WorkflowNodes.IMAGE_COLLECTOR -> "收集到 " + context.imageCount() + " 张图片";
            case WorkflowNodes.PROMPT_ENHANCER -> "已关联 " + context.imageCount() + " 张图片到提示词";
            case WorkflowNodes.ROUTER -> context.getGenerationType() == null
                    ? "路由完成" : "选择 " + context.getGenerationType().getText() + " 模式";
            case WorkflowNodes.HTML_CODE_GENERATOR,
                 WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                 WorkflowNodes.VUE_CODE_GENERATOR -> "代码生成完成（"
                    + (context.getGenerationType() == null ? "" : context.getGenerationType().getText()) + "）";
            case WorkflowNodes.PROJECT_BUILDER -> context.getGenerationType() == CodeGenTypeEnum.VUE_PROJECT
                    ? (context.isBuildSuccess() ? "Vue 工程构建完成" : "Vue 工程构建失败")
                    : "静态文件无需构建";
            default -> StrUtil.blankToDefault(context.getCurrentStep(), "节点执行完成");
        };
    }

    /**
     * 把内部上下文转换为对外结果，路径只返回相对于生成根目录的片段。
     */
    private static WorkflowResult toResult(WorkflowContext context) {
        CodeGenTypeEnum type = context.getGenerationType();
        List<ImageResource> images = context.getImages() == null
                ? List.of()
                : context.getImages().stream().filter(java.util.Objects::nonNull).toList();
        List<String> warnings = context.getWarnings() == null
                ? List.of() : List.copyOf(context.getWarnings());
        return new WorkflowResult(
                context.getWorkflowId(),
                context.getAppId(),
                !context.hasError(),
                context.getCurrentStep(),
                type == null ? null : type.getValue(),
                relativeCodePath(context.getGeneratedCodeDir()),
                relativeCodePath(context.getBuildResultDir()),
                images.size(),
                context.getEnhancedPrompt() == null ? 0 : context.getEnhancedPrompt().length(),
                images,
                warnings,
                context.getErrorMessage(),
                context.elapsedMillis());
    }

    /**
     * 服务端绝对路径不返回客户端，只返回以生成根目录为基准的相对路径。
     */
    private static String relativeCodePath(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!path.startsWith(root) || path.equals(root)) {
                return null;
            }
            return root.relativize(path).toString().replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeErrorMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (StrUtil.isBlank(message)) {
            message = error.getClass().getSimpleName();
        }
        message = message.replace(AppConstant.CODE_OUTPUT_ROOT_DIR, "<code_output>")
                .replace('\\', '/');
        String sanitized = WorkflowImageSupport.sanitizeText(message, 500);
        return StrUtil.isBlank(sanitized) ? "AI 工作流执行失败" : sanitized;
    }

    private static void emitSafely(FluxSink<WorkflowEvent> sink, WorkflowEvent event) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            sink.next(event);
        } catch (Exception e) {
            log.debug("推送工作流事件失败：{}", e.getMessage());
        }
    }
}
