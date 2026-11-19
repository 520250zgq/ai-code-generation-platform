package com.zgq.zgqaicodemother.core;

import com.zgq.zgqaicodemother.ai.AiCodeGeneratorService;
import com.zgq.zgqaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.core.handler.JsonMessageStreamHandler;
import com.zgq.zgqaicodemother.core.parser.CodeParserExecutor;
import com.zgq.zgqaicodemother.core.saver.CodeFileSaverExecutor;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
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
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

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
                HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(appId, userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(appId, userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
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
            case HTML -> processCodeStream(
                    aiCodeGeneratorService.generateHtmlCodeStream(appId, userMessage),
                    CodeGenTypeEnum.HTML, appId);
            case MULTI_FILE -> processCodeStream(
                    aiCodeGeneratorService.generateMultiFileCodeStream(appId, userMessage),
                    CodeGenTypeEnum.MULTI_FILE, appId);
            // TokenStream 只能在订阅时启动，用 defer 保证每次订阅都创建一条全新的模型调用。
            // 普通生成保持调用原有 handle 重载，避免改变既有 SSE 行为；工作流模式显式关闭自动构建。
            case VUE_PROJECT -> Flux.defer(() -> autoBuildVueProject
                    ? jsonMessageStreamHandler.handle(
                            aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage), appId)
                    : jsonMessageStreamHandler.handle(
                            aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage), appId, false));
        };
    }

    /**
     * 通用流式代码处理方法（使用 appId）。
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
                // 使用执行器保存代码
                File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，路径为：{}", savedDir.getAbsolutePath());
            });
        });
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
