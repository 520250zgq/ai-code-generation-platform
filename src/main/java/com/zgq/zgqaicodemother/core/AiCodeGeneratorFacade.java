package com.zgq.zgqaicodemother.core;

import com.zgq.zgqaicodemother.ai.AiCodeGeneratorService;
import com.zgq.zgqaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryGuard;
import com.zgq.zgqaicodemother.ai.resilience.CodeOutputGuard;
import com.zgq.zgqaicodemother.ai.tools.ToolCallGuard;
import com.zgq.zgqaicodemother.core.handler.JsonMessageStreamHandler;
import com.zgq.zgqaicodemother.core.parser.CodeParserExecutor;
import com.zgq.zgqaicodemother.core.saver.CodeFileSaverExecutor;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.preview.PreviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AI 代码生成外观类，组合生成和保存功能。
 *
 * <p>HTML / 多文件模式由模型输出完整文本，流结束后再解析、保存；Vue 工程模式则由模型
 * 通过文件工具直接读写工程文件（创建、读取、局部修改、删除），因此不需要解析器，交给
 * {@link JsonMessageStreamHandler} 转换流式消息，并在流完成后异步构建 Vue 工程。</p>
 *
 * <p>本类同时是三条稳定性机制的接入点：</p>
 * <ul>
 *   <li>重试护轨 {@link AiRetryGuard}：仅对静态模式的文本生成生效，且只在尚未输出内容时重试；</li>
 *   <li>工具循环保护 {@link ToolCallGuard}：为每一轮 Vue 生成登记/清理调用预算；</li>
 *   <li>输出护轨 {@link CodeOutputGuard}：保存前校验代码结构，避免把截断结果落到预览目录。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @Resource
    private AiRetryGuard aiRetryGuard;

    @Resource
    private ToolCallGuard toolCallGuard;

    @Resource
    private PreviewService previewService;

    /**
     * 统一入口：根据类型生成并保存代码（使用 appId）。
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        validateRequest(codeGenTypeEnum, appId);
        AiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiRetryGuard.call("generate-html",
                        () -> aiCodeGeneratorService.generateHtmlCode(appId, userMessage));
                CodeOutputGuard.validate(result);
                yield saveAndMarkPreview(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiRetryGuard.call("generate-multi-file",
                        () -> aiCodeGeneratorService.generateMultiFileCode(appId, userMessage));
                CodeOutputGuard.validate(result);
                yield saveAndMarkPreview(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT ->
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "Vue 工程模式只支持流式生成");
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式，使用 appId）。
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 流式响应
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, true);
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式），可控制 Vue 工程是否自动构建。
     *
     * <p>工作流模式需要在代码生成节点之后单独执行项目构建节点，因此传入
     * {@code autoBuildVueProject=false}，避免 {@link JsonMessageStreamHandler} 在流结束时
     * 异步触发一次构建，与项目构建节点产生竞争。</p>
     *
     * @param userMessage          用户提示词
     * @param codeGenTypeEnum      生成类型
     * @param appId                应用 ID
     * @param autoBuildVueProject  是否在 Vue 工程流结束后自动构建
     * @return 流式响应
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum,
                                                   Long appId, boolean autoBuildVueProject) {
        validateRequest(codeGenTypeEnum, appId);
        AiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return streamWithService(userMessage, codeGenTypeEnum, appId, autoBuildVueProject, aiCodeGeneratorService);
    }

    /**
     * 工作流专用入口：使用与普通聊天隔离的短窗口记忆生成代码。
     *
     * <p>工作流每次都是独立需求，不应读取应用聊天历史；否则多轮历史 HTML 会让输入上下文
     * 越来越大，生成变慢甚至长时间无响应。此方法同时关闭 Vue 自动构建，由项目构建节点统一处理。</p>
     *
     * @param userMessage     增强后的提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 流式响应
     */
    public Flux<String> generateAndSaveCodeStreamForWorkflow(String userMessage,
                                                              CodeGenTypeEnum codeGenTypeEnum,
                                                              Long appId) {
        validateRequest(codeGenTypeEnum, appId);
        AiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getWorkflowAiCodeGeneratorService(appId, codeGenTypeEnum);
        return streamWithService(userMessage, codeGenTypeEnum, appId, false, aiCodeGeneratorService);
    }

    /**
     * 根据已选定的 AI Service 执行对应生成流程。
     */
    private Flux<String> streamWithService(String userMessage, CodeGenTypeEnum codeGenTypeEnum,
                                           Long appId, boolean autoBuildVueProject,
                                           AiCodeGeneratorService aiCodeGeneratorService) {
        return switch (codeGenTypeEnum) {
            // 静态模式的输出是纯文本，重试不会产生副作用；护轨保证只在“尚未输出任何内容”时重试。
            case HTML -> processCodeStream(
                    aiRetryGuard.guardStream("generate-html",
                            () -> aiCodeGeneratorService.generateHtmlCodeStream(appId, userMessage)),
                    CodeGenTypeEnum.HTML, appId);
            case MULTI_FILE -> processCodeStream(
                    aiRetryGuard.guardStream("generate-multi-file",
                            () -> aiCodeGeneratorService.generateMultiFileCodeStream(appId, userMessage)),
                    CodeGenTypeEnum.MULTI_FILE, appId);
            // TokenStream 只能在订阅时启动，用 defer 保证每次订阅都创建一条全新的模型调用。
            // Vue 模式有写文件副作用，不参与自动重试；改为在每轮生成前后登记工具调用预算。
            case VUE_PROJECT -> Flux.defer(() -> {
                toolCallGuard.startTurn(appId);
                Flux<String> codeStream = autoBuildVueProject
                        ? jsonMessageStreamHandler.handle(
                                aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage), appId)
                        : jsonMessageStreamHandler.handle(
                                aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage), appId, false);
                return codeStream.doFinally(signal -> toolCallGuard.finishTurn(appId));
            });
        };
    }

    /**
     * 通用流式代码处理方法（使用 appId）。
     *
     * <p>保存前必须通过输出护轨：模型输出被截断时，宁可让用户看到“生成不完整，请重试”，
     * 也不要把半份代码写进预览目录、让用户以为这就是最终效果。</p>
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @param appId       应用 ID
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        return Flux.defer(() -> {
            // 每次订阅独立收集响应，避免重复订阅时拼接到上次的 JSON。
            StringBuilder codeBuilder = new StringBuilder();
            return codeStream.doOnNext(codeBuilder::append).doOnComplete(() -> {
                // 仅在上游正常完成时解析并保存，异常交由订阅者处理。
                String completeCode = codeBuilder.toString();
                // 使用执行器解析代码
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                validateOutput(parsedResult, codeGenType);
                // 使用执行器保存代码
                File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                // 版本号递增，让前端立刻用新版本参数刷新预览
                previewService.markGenerated(appId, codeGenType);
                log.info("保存成功，路径为：{}，生成类型={}，appId={}",
                        savedDir.getAbsolutePath(), codeGenType.getValue(), appId);
            });
        });
    }

    /**
     * 同步生成路径的保存与预览标记。
     */
    private File saveAndMarkPreview(Object parsedResult, CodeGenTypeEnum codeGenType, Long appId) {
        File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
        previewService.markGenerated(appId, codeGenType);
        log.info("保存成功，路径为：{}，生成类型={}，appId={}",
                savedDir.getAbsolutePath(), codeGenType.getValue(), appId);
        return savedDir;
    }

    /**
     * 按生成类型执行输出护轨。
     */
    private static void validateOutput(Object parsedResult, CodeGenTypeEnum codeGenType) {
        switch (codeGenType) {
            case HTML -> CodeOutputGuard.validate((HtmlCodeResult) parsedResult);
            case MULTI_FILE -> CodeOutputGuard.validate((MultiFileCodeResult) parsedResult);
            default -> {
                // Vue 模式不经过文本解析，结构校验由构建节点负责。
            }
        }
    }

    private void validateRequest(CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型为空");
        }
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        }
    }
}
