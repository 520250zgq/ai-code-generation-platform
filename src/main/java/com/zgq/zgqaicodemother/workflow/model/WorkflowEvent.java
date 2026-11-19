package com.zgq.zgqaicodemother.workflow.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 工作流 SSE 事件。
 *
 * <p>事件只暴露进度和结果，不包含提示词全文、模型原始响应等可能包含用户隐私的内容。</p>
 *
 * @param type     事件类型：workflow_start / node_completed / workflow_completed / workflow_error
 * @param workflowId 本次执行 id
 * @param node     节点 id，工作流级事件为空
 * @param nodeName 节点中文名称
 * @param step     从 1 开始的完成步骤序号
 * @param message  可直接展示的提示
 * @param elapsedMs 从工作流开始到当前事件的耗时
 * @param timestamp 事件产生时间
 * @param result   仅在 workflow_completed / workflow_error 事件中携带
 */
public record WorkflowEvent(
        String type,
        String workflowId,
        String node,
        String nodeName,
        int step,
        String message,
        long elapsedMs,
        Instant timestamp,
        WorkflowResult result) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String TYPE_START = "workflow_start";

    public static final String TYPE_NODE_COMPLETED = "node_completed";

    public static final String TYPE_COMPLETED = "workflow_completed";

    public static final String TYPE_ERROR = "workflow_error";

    /**
     * 工作流开始事件。
     */
    public static WorkflowEvent start(String workflowId, String message) {
        return new WorkflowEvent(TYPE_START, workflowId, null, null, 0, message,
                0L, Instant.now(), null);
    }

    /**
     * 单个节点完成事件。
     */
    public static WorkflowEvent nodeCompleted(String workflowId, String node, String nodeName,
                                              int step, String message, long elapsedMs) {
        return new WorkflowEvent(TYPE_NODE_COMPLETED, workflowId, node, nodeName, step,
                message, elapsedMs, Instant.now(), null);
    }

    /**
     * 工作流正常结束事件，成功和业务失败都使用该类型，结果里的 success 区分。
     */
    public static WorkflowEvent completed(String workflowId, String message, long elapsedMs,
                                          WorkflowResult result) {
        return new WorkflowEvent(TYPE_COMPLETED, workflowId, null, null, 0, message,
                elapsedMs, Instant.now(), result);
    }

    /**
     * 工作流异常结束事件。
     */
    public static WorkflowEvent error(String workflowId, String message, long elapsedMs,
                                      WorkflowResult result) {
        return new WorkflowEvent(TYPE_ERROR, workflowId, null, null, 0, message,
                elapsedMs, Instant.now(), result);
    }
}
