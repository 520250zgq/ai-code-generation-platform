package com.zgq.zgqaicodemother.workflow.demo;

import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流草稿的结构测试：只验证节点顺序和条件分支，不涉及任何业务状态。
 */
class WorkflowDraftTest {

    @Test
    void executesTemporaryNodesInExpectedOrder() {
        List<String> executedNodes = new ArrayList<>();
        for (NodeOutput<MessagesState<String>> output
                : new WorkflowDraft().createWorkflow().stream(Map.of())) {
            // stream 会额外产出 START / END 哨兵节点，业务节点顺序只看真实节点。
            if (!output.isSTART() && !output.isEND()) {
                executedNodes.add(output.node());
            }
        }

        assertEquals(List.of(
                WorkflowNodes.IMAGE_COLLECTOR,
                WorkflowNodes.PROMPT_ENHANCER,
                WorkflowNodes.ROUTER,
                WorkflowNodes.HTML_CODE_GENERATOR,
                WorkflowNodes.PROJECT_BUILDER), executedNodes);
    }

    @Test
    void graphRepresentationContainsAllRealNodes() {
        String mermaid = new WorkflowDraft().createWorkflow()
                .getGraph(org.bsc.langgraph4j.GraphRepresentation.Type.MERMAID)
                .content();

        assertTrue(mermaid.contains(WorkflowNodes.IMAGE_COLLECTOR), mermaid);
        assertTrue(mermaid.contains(WorkflowNodes.ROUTER), mermaid);
        assertTrue(mermaid.contains(WorkflowNodes.VUE_CODE_GENERATOR), mermaid);
    }
}
