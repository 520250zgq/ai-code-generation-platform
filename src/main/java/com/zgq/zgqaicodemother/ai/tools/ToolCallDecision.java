package com.zgq.zgqaicodemother.ai.tools;

/**
 * 单次工具调用前的判定结果。
 *
 * @param allowed 是否允许真正执行工具
 * @param hint    不允许执行时返回给模型的可读提示；允许执行时为 null
 */
public record ToolCallDecision(boolean allowed, String hint) {

    /**
     * 允许执行。
     *
     * @return 放行结果
     */
    public static ToolCallDecision allow() {
        return new ToolCallDecision(true, null);
    }

    /**
     * 拒绝执行，并把提示交给模型，让它自行纠正后续动作。
     *
     * @param hint 可读提示，必须说明“为什么不该继续”和“下一步该做什么”
     * @return 拒绝结果
     */
    public static ToolCallDecision deny(String hint) {
        return new ToolCallDecision(false, hint);
    }
}
