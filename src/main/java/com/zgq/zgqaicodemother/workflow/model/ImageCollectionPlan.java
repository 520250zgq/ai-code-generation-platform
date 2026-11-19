package com.zgq.zgqaicodemother.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图片收集计划。
 *
 * <p>先让 AI 分析需求要哪些图片，再由后端并发调用对应工具，避免把“搜索什么关键词”“画什么架构图”
 * 这类决策散落在代码里。计划本身允许部分字段为空，工作流会根据实际任务数量调用工具。</p>
 *
 * <p>内部任务类使用普通 POJO 而不是 record：LangChain4j 的 JSON 结构化输出需要无参构造
 * 和 setter，普通 POJO 在不同模型供应商下兼容性更好。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageCollectionPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内容图片搜索任务。
     */
    private List<ContentImageTask> contentImageTasks;

    /**
     * 插画图片搜索任务。
     */
    private List<IllustrationTask> illustrationTasks;

    /**
     * 架构图生成任务。
     */
    private List<DiagramTask> diagramTasks;

    /**
     * Logo 生成任务。
     */
    private List<LogoTask> logoTasks;

    /**
     * 是否所有任务都为空。
     *
     * @return 没有任何任务时返回 true
     */
    public boolean isEmpty() {
        return sizeOf(contentImageTasks) == 0
                && sizeOf(illustrationTasks) == 0
                && sizeOf(diagramTasks) == 0
                && sizeOf(logoTasks) == 0;
    }

    /**
     * 统计任务总数，用于日志和限制最大任务数。
     *
     * @return 四类任务数量之和
     */
    public int totalTasks() {
        return sizeOf(contentImageTasks) + sizeOf(illustrationTasks)
                + sizeOf(diagramTasks) + sizeOf(logoTasks);
    }

    private static int sizeOf(List<?> tasks) {
        return tasks == null ? 0 : tasks.size();
    }

    /**
     * 内容图片搜索任务，对应内容图片搜索工具。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentImageTask implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 搜索关键词。
         */
        private String query;
    }

    /**
     * 插画搜索任务，对应 unDraw 插画工具。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IllustrationTask implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 搜索关键词。
         */
        private String query;
    }

    /**
     * 架构图任务，由模型提供 Mermaid 文本。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiagramTask implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Mermaid 图表源码。
         */
        private String mermaidCode;

        /**
         * 架构图用途描述，供提示词引用。
         */
        private String description;
    }

    /**
     * Logo 生成任务，由模型提供设计描述。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogoTask implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Logo 设计描述，例如名称、行业、风格。
         */
        private String description;
    }
}
