package com.zgq.zgqaicodemother.security.prompt;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Prompt 审查结果。
 *
 * <p>{@code allowed=false} 表示必须拒绝：调用方应立即终止请求，既不能继续把用户输入送进模型，
 * 也不能把原始输入写入应用记录或对话历史。此时 {@code text} 固定为 {@code null}，
 * 调用方必须丢弃原始输入，不要自行回退成“用原文继续”。</p>
 *
 * <p>{@code allowed=true} 时 {@code text} 是清洗后可直接送入模型的安全文本：做过 NFKC 归一化、
 * 移除了零宽/控制字符、并中和了伪造的角色分隔符（例如 {@code [SYSTEM]}、{@code <|im_start|>}）。
 * 调用方必须用 {@code text} 替换原始入参，而不是继续使用自己手里的原文；输入为空时
 * {@code text} 为空串。</p>
 *
 * <p>{@code score} 通常是命中的风险类别权重之和（同类只计一次，上限 100）；超长输入按策略直接记
 * 100，自定义黑名单命中同样直接记 100 且不归属任何内置类别，这两类情况下 {@code categories}
 * 不会完整反映分数来源。{@code categories} 是不可变集合且保持命中顺序；{@code reason}
 * 在拒绝时是可直接展示给用户的中文提示，不包含用户原文，放行时仅用于日志和排障。</p>
 *
 * @param allowed    是否允许继续调用模型
 * @param text       可送入模型的安全文本，拒绝时为 null
 * @param score      风险分，0-100
 * @param categories 命中的风险类别
 * @param reason     中文原因说明
 */
public record PromptReviewResult(boolean allowed, String text, int score,
                                 Set<PromptRiskCategory> categories, String reason) {

    /**
     * 统一处理集合不可变性和 null 原因，避免调用方修改审查结果或拿到 null 集合。
     */
    public PromptReviewResult {
        categories = categories == null || categories.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(categories));
        reason = reason == null ? "" : reason;
    }

    /**
     * 是否被拦截，等价于 {@code !allowed()}。
     *
     * @return 被拒绝返回 true
     */
    public boolean blocked() {
        return !allowed;
    }

    /**
     * 是否命中指定风险类别。
     *
     * @param category 风险类别
     * @return 命中返回 true，category 为 null 时返回 false
     */
    public boolean hasCategory(PromptRiskCategory category) {
        return category != null && categories.contains(category);
    }

    /**
     * 构造放行结果。text 为 null 时按空串处理，保证放行结果始终有可用的安全文本。
     *
     * @param text       清洗后的安全文本
     * @param score      风险分
     * @param categories 命中的风险类别
     * @param reason     放行原因（仅用于日志/排障）
     * @return 放行结果
     */
    public static PromptReviewResult allow(String text, int score,
                                           Set<PromptRiskCategory> categories, String reason) {
        return new PromptReviewResult(true, text == null ? "" : text, score, categories, reason);
    }

    /**
     * 构造拒绝结果。拒绝时 text 固定为 null，提示文案不能回显用户原文。
     *
     * @param reason     面向用户的中文原因
     * @param score      风险分
     * @param categories 命中的风险类别
     * @return 拒绝结果
     */
    public static PromptReviewResult reject(String reason, int score,
                                            Set<PromptRiskCategory> categories) {
        return new PromptReviewResult(false, null, score, categories, reason);
    }
}
