package com.zgq.zgqaicodemother.workflow;

import java.util.List;

/**
 * 工作流节点 id 和展示名称。
 *
 * <p>节点 id 同时出现在图定义、日志和 SSE 事件中，集中定义可以避免改名时漏改条件边的映射。
 * 展示名称与需求文档中的步骤一一对应，便于前端直接渲染进度条。</p>
 */
public final class WorkflowNodes {

    public static final String IMAGE_COLLECTOR = "image_collector";

    public static final String PROMPT_ENHANCER = "prompt_enhancer";

    public static final String ROUTER = "router";

    public static final String HTML_CODE_GENERATOR = "html_code_generator";

    public static final String MULTI_FILE_CODE_GENERATOR = "multi_file_code_generator";

    public static final String VUE_CODE_GENERATOR = "vue_code_generator";

    public static final String PROJECT_BUILDER = "project_builder";

    /**
     * 按工作流执行顺序排列的节点，供进度展示和文档生成使用。
     */
    public static final List<String> ORDERED_NODES = List.of(
            IMAGE_COLLECTOR,
            PROMPT_ENHANCER,
            ROUTER,
            HTML_CODE_GENERATOR,
            MULTI_FILE_CODE_GENERATOR,
            VUE_CODE_GENERATOR,
            PROJECT_BUILDER);

    private WorkflowNodes() {
    }

    /**
     * 把节点 id 转换为中文展示名称，未知节点直接返回 id。
     *
     * @param node 节点 id
     * @return 展示名称
     */
    public static String displayName(String node) {
        if (node == null) {
            return "";
        }
        return switch (node) {
            case IMAGE_COLLECTOR -> "图片收集";
            case PROMPT_ENHANCER -> "提示词增强";
            case ROUTER -> "智能路由";
            case HTML_CODE_GENERATOR -> "生成 HTML 网站";
            case MULTI_FILE_CODE_GENERATOR -> "生成多文件网站";
            case VUE_CODE_GENERATOR -> "生成 Vue 工程";
            case PROJECT_BUILDER -> "项目构建";
            default -> node;
        };
    }
}
