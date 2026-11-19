package com.zgq.zgqaicodemother.workflow.node;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码生成节点基类。
 *
 * <p>三种生成模式复用同一套流程：取增强后的提示词 -> 调用对应 AI 生成服务 -> 校验生成目录。
 * 差异只体现在 {@link #type()} 和节点 id 上，因此用模板方法把公共逻辑收敛到基类，避免三份
 * 容易不一致的复制代码。</p>
 *
 * <p>生成失败不会抛出异常，而是写入上下文的 errorMessage，让工作流进入项目构建节点并正常
 * 结束在 END；这样调用方仍能拿到已完成步骤的上下文和明确的失败原因。</p>
 */
@Slf4j
public abstract class AbstractCodeGeneratorNode {

    protected final AiCodeGeneratorFacade aiCodeGeneratorFacade;

    protected final WorkflowProperties properties;

    protected AbstractCodeGeneratorNode(AiCodeGeneratorFacade aiCodeGeneratorFacade,
                                        WorkflowProperties properties) {
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
        this.properties = properties;
    }

    /**
     * 创建节点动作。
     *
     * @return LangGraph4j 节点动作
     */
    public AsyncNodeAction<MessagesState<String>> create() {
        return node_async(this::execute);
    }

    /**
     * 当前节点负责的代码生成类型。
     *
     * @return 生成类型
     */
    protected abstract CodeGenTypeEnum type();

    /**
     * 节点 id。
     *
     * @return 节点 id
     */
    public abstract String nodeId();

    private Map<String, Object> execute(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.require(state);
        if (context.hasError()) {
            context.setCurrentStep("代码生成跳过（上游失败）");
            return WorkflowState.save(context);
        }
        CodeGenTypeEnum expectedType = type();
        if (context.getGenerationType() != expectedType) {
            context.fail("代码生成失败", "路由类型与生成节点不一致");
            return WorkflowState.save(context);
        }
        if (context.getAppId() == null || context.getAppId() <= 0) {
            context.fail("代码生成失败", "应用 ID 无效，无法确定代码保存目录");
            return WorkflowState.save(context);
        }
        String prompt = StrUtil.isNotBlank(context.getEnhancedPrompt())
                ? context.getEnhancedPrompt() : context.getOriginalPrompt();
        if (StrUtil.isBlank(prompt)) {
            context.fail("代码生成失败", "生成提示词为空");
            return WorkflowState.save(context);
        }
        context.setCurrentStep("代码生成中（" + expectedType.getText() + "）");
        long startNanos = System.nanoTime();
        try {
            // 工作流使用隔离记忆，避免应用历史对话把提示词撑大；Vue 构建交给项目构建节点。
            Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStreamForWorkflow(
                    prompt, expectedType, context.getAppId());
            Duration timeout = properties.getCodeGenerationTimeout();
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                timeout = Duration.ofMinutes(10);
            }
            log.info("开始等待 AI 代码生成，workflowId={}，type={}，timeout={}s",
                    context.getWorkflowId(), expectedType.getValue(), timeout.toSeconds());
            // timeout 操作符在模型长时间没有任何响应时主动抛出 TimeoutException，
            // 避免底层流式请求卡死时整个工作流一直挂起。
            codeStream.timeout(timeout).blockLast();
            Path outputDir = outputDirectory(context.getAppId(), expectedType);
            if (!Files.isDirectory(outputDir)) {
                context.fail("代码生成失败", "代码生成完成但未找到输出目录");
                log.error("代码生成节点未找到输出目录，workflowId={}，dir={}",
                        context.getWorkflowId(), outputDir);
                return WorkflowState.save(context);
            }
            context.setGeneratedCodeDir(outputDir.toString());
            context.setCurrentStep("代码生成完成（" + expectedType.getText() + "）");
            log.info("代码生成节点完成，workflowId={}，appId={}，type={}，耗时={}ms",
                    context.getWorkflowId(), context.getAppId(), expectedType.getValue(),
                    (System.nanoTime() - startNanos) / 1_000_000);
        } catch (Exception e) {
            log.error("代码生成节点失败，workflowId={}，appId={}，type={}",
                    context.getWorkflowId(), context.getAppId(), expectedType.getValue(), e);
            context.fail("代码生成失败", safeErrorMessage(e));
        }
        return WorkflowState.save(context);
    }

    /**
     * 生成目录与代码保存器保持一致：{@code {CODE_OUTPUT_ROOT_DIR}/{type}_{appId}}。
     * 目录名由枚举值和数字 appId 组成，不包含客户端输入，避免路径穿越。
     */
    private static Path outputDirectory(Long appId, CodeGenTypeEnum type) {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, type.getValue() + "_" + appId)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 生成给用户看的错误信息，去掉服务器绝对路径和过长的堆栈文本。
     */
    private static String safeErrorMessage(Exception e) {
        String message = e.getMessage();
        if (StrUtil.isBlank(message)) {
            message = "AI 代码生成中断，请重试";
        }
        message = message.replace(AppConstant.CODE_OUTPUT_ROOT_DIR, "<code_output>")
                .replace('\\', '/');
        message = WorkflowImageSupport.sanitizeText(message, 300);
        return StrUtil.isBlank(message) ? "AI 代码生成中断，请重试" : message;
    }
}
