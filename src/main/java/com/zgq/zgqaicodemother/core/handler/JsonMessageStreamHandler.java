package com.zgq.zgqaicodemother.core.handler;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import com.zgq.zgqaicodemother.ai.tools.ToolCallGuard;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.preview.PreviewService;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON 消息流式处理器。
 *
 * <p>Vue 工程模式返回的是 LangChain4j 的 {@link TokenStream}：模型正文通过 text 事件返回，
 * 文件操作通过 JSON 格式的 tool_calls 事件返回。本处理器把工具执行结果交给
 * {@code ToolManager} 生成精简进度，再转换为前端 SSE 可消费的文本块，保留现有的纯文本流协议，
 * 避免影响前端已有解析逻辑。</p>
 *
 * <p>当流式输出正常完成时，Vue 工程源码已经全部落盘。此时触发 {@link VueProjectBuilder}
 * 在虚拟线程中异步执行 npm 安装和打包，既能让用户立即看到生成完成，又不会阻塞主流程。
 * 构建开始/结束时同步更新预览状态，前端据此在构建完成后自动刷新到最新版本。</p>
 *
 * <p>工具调用有两层保护：本类保留“单轮硬上限”，超过后直接终止流；
 * {@link ToolCallGuard} 负责语义层面的重复调用拦截。两层互补：前者防住任何形式的暴走，
 * 后者在触顶之前就让模型自我纠正，避免用户白等几分钟才收到失败提示。</p>
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    /**
     * 单轮 Vue 生成允许的工具调用次数上限。
     *
     * <p>LangChain4j 1.1.0 的流式工具循环不会应用
     * {@code maxSequentialToolsInvocations}，如果模型发生幻觉可能无限调用工具。
     * 这里在流式回调中独立计数并主动终止，给前端一个可读的错误提示。</p>
     */
    static final int MAX_TOOL_EXECUTIONS_PER_TURN = 40;

    private final VueProjectBuilder vueProjectBuilder;

    private final ToolManager toolManager;

    private final PreviewService previewService;

    public JsonMessageStreamHandler(VueProjectBuilder vueProjectBuilder,
                                    ToolManager toolManager,
                                    PreviewService previewService) {
        this.vueProjectBuilder = vueProjectBuilder;
        this.toolManager = toolManager;
        this.previewService = previewService;
    }

    /**
     * 处理 Vue 工程的 TokenStream。
     *
     * @param tokenStream AI Service 返回的 TokenStream
     * @param appId       应用 id，同时用于定位 Vue 工程目录
     * @return 兼容现有 SSE 协议的文本流
     */
    public Flux<String> handle(TokenStream tokenStream, Long appId) {
        return doHandle(tokenStream, appId, true);
    }

    /**
     * 处理 Vue 工程的 TokenStream，并决定是否在流结束后自动构建。
     *
     * <p>工作流模式把构建交给独立的项目构建节点，因此传入 {@code autoBuild=false}，
     * 避免同一工程被异步构建和同步构建重复执行。</p>
     *
     * @param tokenStream AI Service 返回的 TokenStream
     * @param appId       应用 id，同时用于定位 Vue 工程目录
     * @param autoBuild   流正常结束后是否在虚拟线程中自动构建
     * @return 兼容现有 SSE 协议的文本流
     */
    public Flux<String> handle(TokenStream tokenStream, Long appId, boolean autoBuild) {
        return doHandle(tokenStream, appId, autoBuild);
    }

    private Flux<String> doHandle(TokenStream tokenStream, Long appId, boolean autoBuild) {
        if (tokenStream == null) {
            return Flux.error(new BusinessException(ErrorCode.PARAMS_ERROR, "TokenStream 不能为空"));
        }
        if (appId == null || appId <= 0) {
            return Flux.error(new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效"));
        }
        return Flux.create(sink -> {
            AtomicInteger toolExecutionCount = new AtomicInteger();
            tokenStream
                    .onPartialResponse(partialResponse -> {
                        if (!sink.isCancelled() && StrUtil.isNotEmpty(partialResponse)) {
                            sink.next(partialResponse);
                        }
                    })
                    .onToolExecuted(toolExecution -> {
                        if (sink.isCancelled()) {
                            return;
                        }
                        int currentCount = toolExecutionCount.incrementAndGet();
                        if (currentCount > MAX_TOOL_EXECUTIONS_PER_TURN) {
                            BusinessException error = new BusinessException(ErrorCode.OPERATION_ERROR,
                                    "工具调用次数超过上限（" + MAX_TOOL_EXECUTIONS_PER_TURN
                                            + " 次），已停止生成，请简化需求后重试");
                            sink.error(error);
                            // 抛出异常中止 LangChain4j 的流式工具循环，避免继续写文件或反复调用模型。
                            throw new ToolExecutionLimitException();
                        }
                        // 不同工具的输出结构不同：由 ToolManager 找到对应策略，转换成精简的进度文本。
                        String progress = toolManager.generateToolExecutedResult(toolExecution);
                        if (StrUtil.isNotBlank(progress)) {
                            sink.next(progress);
                        }
                    })
                    .onCompleteResponse(response -> {
                        if (sink.isCancelled()) {
                            log.info("Vue 工程流式输出已取消，跳过构建，appId={}", appId);
                            return;
                        }
                        log.info("Vue 工程流式输出完成，appId={}，工具调用次数={}",
                                appId, toolExecutionCount.get());
                        try {
                            sink.complete();
                        } finally {
                            if (autoBuild) {
                                // 构建在虚拟线程中执行，这里只负责提交任务，不会阻塞 SSE 完成。
                                triggerBuild(appId);
                            } else {
                                log.info("工作流模式跳过自动构建，由项目构建节点处理，appId={}", appId);
                            }
                        }
                    })
                    // 工具循环熔断属于可读的业务结果，包成 BusinessException 让 SSE 直接展示原因，
                    // 而不是被统一改写成“AI 生成中断”。
                    .onError(error -> sink.error(toReadableError(error, appId)))
                    .start();
        });
    }

    /**
     * 把工具层的熔断异常转换为面向用户的业务异常；其他异常原样透传。
     */
    private static Throwable toReadableError(Throwable error, Long appId) {
        if (error instanceof ToolCallGuard.ToolCallLimitException limitException) {
            log.warn("工具调用循环保护已终止生成，appId={}，原因={}", appId, limitException.getMessage());
            return new BusinessException(ErrorCode.OPERATION_ERROR, limitException.getMessage());
        }
        return error;
    }

    /**
     * 触发 Vue 工程的异步构建，并在构建结束时更新预览状态。
     *
     * <p>构建器内部已经捕获异常，这里再包一层，确保任何构建相关问题都不会影响主流程。</p>
     *
     * @param appId 应用 id
     */
    private void triggerBuild(Long appId) {
        try {
            String projectPath = FileWriteTool.projectRoot(appId).toString();
            log.info("Vue 工程流式输出完成，提交异步构建任务，appId={}，工程目录={}", appId, projectPath);
            // 先标记 BUILDING，前端在构建期间不会把旧 dist 当成最新效果。
            previewService.markBuildStarted(appId);
            vueProjectBuilder.buildProjectAsync(projectPath,
                    success -> previewService.markBuildFinished(appId, success));
        } catch (Exception e) {
            log.error("提交 Vue 项目构建任务失败，appId={}", appId, e);
            // 构建任务都没提交成功，不能一直停留在 BUILDING。
            previewService.markBuildFinished(appId, false);
        }
    }

    /**
     * 工具调用超限时用于中断 LangChain4j 流式回调链的内部异常。
     * 对订阅者的错误已经通过 {@code sink.error} 发出，这里不再携带重复信息。
     */
    private static final class ToolExecutionLimitException extends RuntimeException {

        private ToolExecutionLimitException() {
            super(null, null, false, false);
        }
    }
}
