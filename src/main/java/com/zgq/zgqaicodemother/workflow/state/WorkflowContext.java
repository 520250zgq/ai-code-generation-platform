package com.zgq.zgqaicodemother.workflow.state;

import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流共享状态。
 *
 * <p>每个工作流实例拥有独立的上下文对象，节点只读取自己需要的字段，并把本步骤结果写回上下文。
 * 该对象不保存任何 Spring Bean、模型客户端或线程资源，因此可以安全地在图的状态中传递，
 * 也便于序列化后排查问题。</p>
 *
 * <p>注意：上下文会跨节点共享，节点实现必须是线程安全的；禁止把请求级可变字段放到节点实例里。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 单次工作流执行 id，用于日志串联和 SSE 事件关联。
     */
    private String workflowId;

    /**
     * 目标应用 id，生成目录和 AI 对话记忆都以它为准。
     */
    private Long appId;

    /**
     * 触发本次工作流的用户 id，仅用于审计日志。
     */
    private Long userId;

    /**
     * 用户原始需求，未被任何图片素材污染，智能路由使用这份文本。
     */
    private String originalPrompt;

    /**
     * 当前执行到的节点名称，供前端展示进度。
     */
    private String currentStep;

    /**
     * AI 生成的图片收集计划，便于排查“为什么没有收集到某类图片”。
     */
    private ImageCollectionPlan imageCollectionPlan;

    /**
     * 聚合后的图片素材，顺序固定为内容图、插画、架构图、Logo。
     */
    @Builder.Default
    private List<ImageResource> images = new ArrayList<>();

    /**
     * 关联图片素材后的提示词，代码生成节点使用。
     */
    private String enhancedPrompt;

    /**
     * 智能路由选择的代码生成类型。
     */
    private CodeGenTypeEnum generationType;

    /**
     * 代码生成目录的绝对路径，仅服务端使用，不直接返回给客户端。
     */
    private String generatedCodeDir;

    /**
     * 构建产物目录的绝对路径。非 Vue 工程等于生成目录。
     */
    private String buildResultDir;

    /**
     * 构建是否成功。HTML / 多文件模式不需要构建，视为成功。
     */
    @Builder.Default
    private boolean buildSuccess = false;

    /**
     * 首个导致流程无法继续的错误；非空时工作流会跳过后续需要产物的节点。
     */
    private String errorMessage;

    /**
     * 非致命告警，例如某个图片渠道未配置、架构图 CLI 不可用。
     * 这些信息不阻断工作流，但需要返回给调用方以便排查素材缺失。
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * 工作流开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 工作流结束时间。
     */
    private LocalDateTime finishedAt;

    /**
     * 记录一条非致命告警，最多保留 {@value #MAX_WARNINGS} 条，避免异常场景无限增长。
     *
     * @param warning 告警内容，空白内容会被忽略
     */
    public void addWarning(String warning) {
        if (warning == null || warning.isBlank()) {
            return;
        }
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(trimToLength(warning, MAX_WARNING_LENGTH));
        }
    }

    /**
     * 标记工作流失败。只保留第一个错误，后续节点不应覆盖最初的失败原因。
     *
     * @param step  失败步骤
     * @param error 错误信息
     */
    public void fail(String step, String error) {
        this.currentStep = step;
        if (errorMessage == null || errorMessage.isBlank()) {
            this.errorMessage = trimToLength(error, MAX_ERROR_LENGTH);
        }
    }

    /**
     * 是否已经出现阻断性错误。
     *
     * @return 存在错误信息时返回 true
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }

    /**
     * 统计已收集图片数量，空列表安全返回 0。
     *
     * @return 图片数量
     */
    public int imageCount() {
        return images == null ? 0 : images.size();
    }

    /**
     * 计算当前工作流耗时，结束时间未设置时按当前时间计算。
     *
     * @return 毫秒数，开始时间为空时返回 0
     */
    public long elapsedMillis() {
        if (startedAt == null) {
            return 0L;
        }
        LocalDateTime end = finishedAt == null ? LocalDateTime.now() : finishedAt;
        return Math.max(0L, java.time.Duration.between(startedAt, end).toMillis());
    }

    /**
     * 结束计时。
     */
    public void finish() {
        this.finishedAt = LocalDateTime.now();
    }

    private static final int MAX_WARNINGS = 30;

    private static final int MAX_WARNING_LENGTH = 500;

    private static final int MAX_ERROR_LENGTH = 2000;

    private static String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        // 告警会进入日志和前端展示：压缩换行/控制字符，避免多行内容破坏日志格式。
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        boolean lastWhitespace = false;
        for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
            char current = value.charAt(i);
            if (Character.isWhitespace(current)) {
                if (!lastWhitespace && builder.length() > 0) {
                    builder.append(' ');
                    lastWhitespace = true;
                }
                continue;
            }
            if (Character.isISOControl(current)) {
                continue;
            }
            builder.append(current);
            lastWhitespace = false;
        }
        return builder.toString().trim();
    }
}
