package com.zgq.zgqaicodemother.security.prompt;

import lombok.Getter;

/**
 * Prompt 审查场景。
 *
 * <p>用户自然语言会从三个入口直接进入系统提示词为“代码生成专家”的模型上下文：
 * 创建应用（{@code POST /app/add} 的 initPrompt）、对话生成（{@code GET /app/chat/gen/code}
 * 的 message）和 AI 工作流（{@code GET /workflow/execute/stream} 的 prompt）。
 * 审查策略按场景区分严格程度：创建应用是第一次把需求写入应用记录、后续对话也会继承这段需求，
 * 因此使用比配置值更低的拦截阈值；对话和工作流沿用配置阈值。</p>
 *
 * <p>新增入口时必须显式选择场景，不要复用“最近的一个”：场景决定拦截阈值，也决定日志里
 * 能定位到是哪条链路放过了可疑输入。</p>
 */
@Getter
public enum PromptScene {

    /**
     * 创建应用。拦截阈值在配置值基础上下浮 10 分，是最严格的场景。
     */
    APP_CREATE("应用创建"),

    /**
     * 应用对话生成代码。
     */
    CHAT("对话生成"),

    /**
     * AI 工作流执行。
     */
    WORKFLOW("AI 工作流");

    /**
     * 场景中文名，用于日志和面向调用方的原因说明。
     */
    private final String text;

    PromptScene(String text) {
        this.text = text;
    }
}
