package com.zgq.zgqaicodemother.workflow.model;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次代码质检的完整结果。
 *
 * <p>该对象既会写入工作流上下文（用于条件边决定“通过 / 修复 / 终止”），也会返回给调用方，
 * 因此除错误和建议外，还记录了检查了多少文件、是否因为长度上限被截断，便于排查
 * “为什么质检没有发现这个问题”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheckResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否通过质检。静态检查发现的问题一票否决，AI 复核未通过也判为不通过。
     */
    private Boolean valid;

    /**
     * 错误列表，来自确定性静态检查和 AI 复核。
     */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /**
     * 改进建议，只用于拼接修复提示词，不阻断工作流。
     */
    @Builder.Default
    private List<String> suggestions = new ArrayList<>();

    /**
     * 实际参与检查的文件相对路径。
     */
    @Builder.Default
    private List<String> checkedFiles = new ArrayList<>();

    /**
     * 实际读取的代码字符数。
     */
    private int checkedChars;

    /**
     * 是否因为文件数量或字符数上限而只检查了部分代码。
     */
    private boolean truncated;

    /**
     * 结果来源：{@code static+ai}、{@code static}（AI 复核失败时降级）或 {@code disabled}。
     */
    private String source;

    /**
     * 质检耗时，单位毫秒。
     */
    private long durationMs;

    /**
     * 是否通过质检。
     *
     * @return {@code valid=true} 时返回 true
     */
    public boolean passed() {
        return Boolean.TRUE.equals(valid);
    }

    /**
     * 错误条数。
     *
     * @return 错误数量
     */
    public int errorCount() {
        return errors == null ? 0 : errors.size();
    }

    /**
     * 生成适合日志和进度展示的摘要。
     *
     * @param maxLength 最大长度
     * @return 摘要文本
     */
    public String summary(int maxLength) {
        StringBuilder builder = new StringBuilder();
        builder.append(passed() ? "代码质检通过" : "代码质检未通过");
        builder.append("，检查 ").append(checkedFiles == null ? 0 : checkedFiles.size()).append(" 个文件");
        if (errorCount() > 0) {
            builder.append("，发现 ").append(errorCount()).append(" 个问题");
        }
        if (truncated) {
            builder.append("（代码超出长度上限，已截断检查）");
        }
        return StrUtil.maxLength(builder.toString(), maxLength <= 0 ? Integer.MAX_VALUE : maxLength);
    }
}
