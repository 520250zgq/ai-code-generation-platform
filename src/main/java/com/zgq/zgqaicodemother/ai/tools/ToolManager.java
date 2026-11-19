package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import dev.langchain4j.service.tool.ToolExecution;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI 工具管理器（策略工厂）。
 *
 * <p>Spring 启动时会把所有 {@link BaseTool} 实现注入进来，管理器按工具名称建立索引。
 * {@code AiCodeGeneratorServiceFactory} 通过 {@link #getTools()} 一次性绑定全部工具；
 * {@code JsonMessageStreamHandler} 通过 {@link #generateToolExecutedResult(ToolExecution)}
 * 取得对应策略生成的进度文本，不需要在流式处理器里写 if/else 区分工具类型。</p>
 *
 * <p>新增工具只需要：实现 {@link BaseTool}、标注 {@code @Component}、把方法名作为
 * {@link BaseTool#getToolName()} 返回。管理器会自动注册，工厂和流式处理器都无需修改。</p>
 */
@Component
public class ToolManager {

    /**
     * 未知工具的兜底进度长度上限。
     */
    static final int MAX_FALLBACK_LENGTH = 200;

    /**
     * 工具名称到策略实现的映射，按名称排序保证工具绑定顺序稳定。
     */
    private final Map<String, BaseTool> toolMap;

    /**
     * 预先展开的工具数组，直接传给 AiServices，避免每次绑定都重新创建。
     */
    private final BaseTool[] tools;

    /**
     * 构造时完成工具注册和重复名称校验。
     *
     * @param baseTools 容器中的全部工具实现，允许为空（未启用工具的模式）
     */
    public ToolManager(List<BaseTool> baseTools) {
        Map<String, BaseTool> sortedTools = new TreeMap<>();
        if (baseTools != null) {
            for (BaseTool baseTool : baseTools) {
                if (baseTool == null || StrUtil.isBlank(baseTool.getToolName())) {
                    throw new IllegalStateException("AI 工具名称不能为空");
                }
                BaseTool previous = sortedTools.put(baseTool.getToolName(), baseTool);
                if (previous != null) {
                    throw new IllegalStateException("AI 工具名称重复：" + baseTool.getToolName()
                            + "，请检查 " + previous.getClass().getSimpleName()
                            + " 与 " + baseTool.getClass().getSimpleName());
                }
            }
        }
        this.toolMap = Collections.unmodifiableMap(new LinkedHashMap<>(sortedTools));
        this.tools = this.toolMap.values().toArray(BaseTool[]::new);
    }

    /**
     * 获取全部已注册工具，用于绑定到 AI Service。
     *
     * <p>返回副本，避免调用方修改管理器内部顺序。</p>
     *
     * @return 工具数组
     */
    public BaseTool[] getTools() {
        return tools.clone();
    }

    /**
     * 获取全部工具名称，用于拼接模型可见的错误提示。
     *
     * @return 以顿号分隔的工具名称
     */
    public String getToolNames() {
        return String.join("、", toolMap.keySet());
    }

    /**
     * 按名称查找工具策略。
     *
     * @param toolName 工具名称
     * @return 对应策略；未注册时返回 null
     */
    public BaseTool getTool(String toolName) {
        if (StrUtil.isBlank(toolName)) {
            return null;
        }
        return toolMap.get(toolName);
    }

    /**
     * 把任意的工具执行结果转换为前端可读的流式进度。
     *
     * @param toolExecution 工具执行结果，允许为 null
     * @return Markdown 进度文本；无有效内容时返回空字符串
     */
    public String generateToolExecutedResult(ToolExecution toolExecution) {
        if (toolExecution == null) {
            return "";
        }
        BaseTool tool = toolExecution.request() == null ? null : getTool(toolExecution.request().name());
        String progress = tool == null ? fallbackProgress(toolExecution) : tool.generateToolExecutedResult(toolExecution);
        return sanitize(progress);
    }

    /**
     * 未注册工具的兜底进度：保留工具名和结果第一行，便于排查模型幻觉。
     */
    private String fallbackProgress(ToolExecution toolExecution) {
        String toolName = toolExecution.request() == null
                ? "未知工具" : StrUtil.blankToDefault(toolExecution.request().name(), "未知工具");
        String firstLine = StrUtil.blankToDefault(toolExecution.result(), "工具执行完成")
                .lines()
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .map(String::trim)
                .orElse("工具执行完成");
        return "\n\n> 🔧 " + toolName + "：" + StrUtil.maxLength(firstLine, MAX_FALLBACK_LENGTH) + "\n\n";
    }

    /**
     * 双重保险：即使未来新增的工具误返回绝对路径，也不能通过 SSE 暴露服务器目录。
     */
    private static String sanitize(String progress) {
        if (StrUtil.isEmpty(progress)) {
            return "";
        }
        String normalizedRoot = AppConstant.CODE_OUTPUT_ROOT_DIR.replace('\\', '/');
        return progress.replace('\\', '/').replace(normalizedRoot, "");
    }
}
