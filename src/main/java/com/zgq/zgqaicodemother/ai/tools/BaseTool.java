package com.zgq.zgqaicodemother.ai.tools;

import dev.langchain4j.service.tool.ToolExecution;

/**
 * AI 工具策略接口。
 *
 * <p>每种文件工具只负责一件事，并自行决定“工具执行结果”如何转成前端可读的流式进度。
 * {@link ToolManager} 以工具名称为键维护策略集合，因此新增工具只需实现本接口并注册为
 * Spring Bean，不需要修改流式处理器中的 if/else 分支。</p>
 *
 * <p>{@link #getToolName()} 必须与 {@code @Tool} 方法名完全一致：LangChain4j 执行完工具后
 * 返回的名称就是方法名，管理器依赖该名称找到对应策略。</p>
 */
public interface BaseTool {

    /**
     * 工具名称，必须与 {@code @Tool} 方法名一致。
     *
     * @return 工具名称
     */
    String getToolName();

    /**
     * 把工具执行结果包装成适合 SSE 展示的进度文本。
     *
     * <p>工具结果会进入对话记忆，可能包含文件内容；流式进度只应展示简短摘要，
     * 不能把完整文件内容或服务器绝对路径推送给前端。</p>
     *
     * @param toolExecution 框架回调的工具执行结果，可能为 null
     * @return 形如 {@code "\n\n> 📄 文件写入成功：src/App.vue\n\n"} 的 Markdown 文本
     */
    String generateToolExecutedResult(ToolExecution toolExecution);
}
