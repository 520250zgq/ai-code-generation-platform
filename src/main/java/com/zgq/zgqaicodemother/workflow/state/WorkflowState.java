package com.zgq.zgqaicodemother.workflow.state;

import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.util.Map;
import java.util.Objects;

/**
 * 工作流状态与图框架之间的适配层。
 *
 * <p>LangGraph4j 的 {@link MessagesState} 只接受 {@code Map<String, Object>} 形式的状态更新，
 * 而业务状态是一个强类型对象。把转换集中在这里，节点和测试都不需要直接操作图的 Map，
 * 也避免各处硬编码状态 key。</p>
 */
public final class WorkflowState {

    /**
     * 工作流上下文在 MessagesState 中的 key。
     */
    public static final String CONTEXT_KEY = "workflowContext";

    private WorkflowState() {
    }

    /**
     * 从图状态中取出工作流上下文。
     *
     * @param state 图状态，允许为 null
     * @return 工作流上下文，不存在时返回 null
     */
    public static WorkflowContext get(MessagesState<String> state) {
        if (state == null) {
            return null;
        }
        Object value = state.data().get(CONTEXT_KEY);
        return value instanceof WorkflowContext context ? context : null;
    }

    /**
     * 从节点输出中取出工作流上下文。
     *
     * @param output 节点输出，允许为 null
     * @return 工作流上下文，不存在时返回 null
     */
    public static WorkflowContext get(NodeOutput<MessagesState<String>> output) {
        return output == null ? null : get(output.state());
    }

    /**
     * 从图状态中取出工作流上下文，不存在时直接抛出异常。
     *
     * <p>上下文缺失说明图输入或节点返回值的装配有 bug，属于不可恢复的编程错误，
     * 让异常向上抛出比继续用空对象执行更安全。</p>
     *
     * @param state 图状态
     * @return 工作流上下文
     */
    public static WorkflowContext require(MessagesState<String> state) {
        WorkflowContext context = get(state);
        if (context == null) {
            throw new IllegalStateException("工作流状态中缺少 WorkflowContext");
        }
        return context;
    }

    /**
     * 节点返回的状态增量。
     *
     * @param context 工作流上下文，不能为空
     * @return 可直接交给 LangGraph4j 的不可变 Map
     */
    public static Map<String, Object> save(WorkflowContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return Map.of(CONTEXT_KEY, context);
    }

    /**
     * 创建初始状态。
     *
     * @param context 初始上下文
     * @return 图输入 Map
     */
    public static Map<String, Object> initialInput(WorkflowContext context) {
        return save(context);
    }
}
