package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * AI 代码生成服务。
 *
 * <p>所有方法都以 appId 作为对话记忆 id，同一个应用的多轮对话共享上下文；
 * 不同应用之间的消息完全隔离。</p>
 */
public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码，并在完整响应后解析为结构化结果。
     *
     * @param appId       应用 id，同时作为对话记忆 id
     * @param userMessage 用户消息
     * @return 完整 HTML 代码结果 DTO
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 生成 HTML、CSS、JavaScript，并在完整响应后解析为结构化结果。
     *
     * @param appId       应用 id，同时作为对话记忆 id
     * @param userMessage 用户消息
     * @return 完整多文件代码结果 DTO
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 流式生成 HTML 模式的结果。每个元素都是模型输出 JSON 文本的一段，
     * 并不保证是完整 JSON，也不保证恰好对应一个代码字符或 token。
     *
     * @param appId       应用 id，同时作为对话记忆 id
     * @param userMessage 用户消息
     * @return JSON 响应文本片段流
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 流式生成多文件模式的结果。每个元素都是模型输出 JSON 文本的一段，
     * 调用方需累计完整响应后解析 JSON，才能安全取得各文件内容。
     *
     * @param appId       应用 id，同时作为对话记忆 id
     * @param userMessage 用户消息
     * @return JSON 响应文本片段流
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 流式生成 Vue 工程：模型通过文件工具创建工程文件，修改时读取并局部改写已有文件。
     *
     * <p>返回 {@link TokenStream} 而不是 {@code Flux<String>}，是因为工具调用期间模型可能
     * 长时间只返回 tool_calls、不返回正文；调用方需要在工具执行完成后立即把进度推送给前端，
     * 这正是 {@link TokenStream#onToolExecuted} 提供的能力。</p>
     *
     * <p>方法必须声明 {@link MemoryId}：工具方法通过 {@code @ToolMemoryId} 取得同一个
     * memoryId（appId），并据此读写该应用独立的工程目录。</p>
     *
     * @param appId       应用 id，同时作为对话记忆 id 注入工具
     * @param userMessage 用户消息
     * @return 可订阅的 TokenStream
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId Long appId, @UserMessage String userMessage);
}
