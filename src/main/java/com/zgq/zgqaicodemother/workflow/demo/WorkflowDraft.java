package com.zgq.zgqaicodemother.workflow.demo;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 工作流开发第 1 步的草稿：只定义结构与边，不记录任何业务状态。
 *
 * <p>对应开发步骤：先让图能完整跑通，节点内部只打印临时输出，确认节点顺序、条件分支和
 * 结束位置正确，再逐步替换为真实的业务实现。这样可以把“流程编排错误”和“业务实现错误”
 * 分开排查。</p>
 *
 * <p>图片收集在正式实现中是一个节点，内部再并发调用四个图片工具；草稿阶段保持同样的
 * 7 个节点，避免演示图和最终执行图出现两套结构。</p>
 */
@Slf4j
public class WorkflowDraft {

    /**
     * 创建只包含临时输出节点的工作流图。
     *
     * @return 已编译、可直接执行的工作流
     */
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            return new MessagesStateGraph<String>()
                    .addNode(WorkflowNodes.IMAGE_COLLECTOR, temporaryNode("图片收集（临时输出）"))
                    .addNode(WorkflowNodes.PROMPT_ENHANCER, temporaryNode("提示词增强（临时输出）"))
                    .addNode(WorkflowNodes.ROUTER, temporaryNode("智能路由（临时输出）"))
                    .addNode(WorkflowNodes.HTML_CODE_GENERATOR, temporaryNode("HTML 生成（临时输出）"))
                    .addNode(WorkflowNodes.MULTI_FILE_CODE_GENERATOR, temporaryNode("多文件生成（临时输出）"))
                    .addNode(WorkflowNodes.VUE_CODE_GENERATOR, temporaryNode("Vue 生成（临时输出）"))
                    .addNode(WorkflowNodes.PROJECT_BUILDER, temporaryNode("项目构建（临时输出）"))
                    .addEdge(START, WorkflowNodes.IMAGE_COLLECTOR)
                    .addEdge(WorkflowNodes.IMAGE_COLLECTOR, WorkflowNodes.PROMPT_ENHANCER)
                    .addEdge(WorkflowNodes.PROMPT_ENHANCER, WorkflowNodes.ROUTER)
                    // 草稿阶段固定走 HTML 分支，正式实现由智能路由结果决定。
                    .addConditionalEdges(WorkflowNodes.ROUTER,
                            edge_async(state -> "html"),
                            Map.of(
                                    "html", WorkflowNodes.HTML_CODE_GENERATOR,
                                    "multi_file", WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                                    "vue", WorkflowNodes.VUE_CODE_GENERATOR))
                    .addEdge(WorkflowNodes.HTML_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.MULTI_FILE_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.VUE_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.PROJECT_BUILDER, END)
                    .compile();
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工作流草稿创建失败：" + e.getMessage());
        }
    }

    /**
     * 临时节点：只打印执行位置，不读取也不写入状态。
     */
    private static org.bsc.langgraph4j.action.AsyncNodeAction<MessagesState<String>> temporaryNode(String name) {
        return node_async(state -> {
            log.info("[工作流草稿] 执行节点：{}", name);
            return Map.of();
        });
    }
}
