package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import com.zgq.zgqaicodemother.workflow.support.PromptEnhancer;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 提示词增强工作节点。
 *
 * <p>把图片素材与原始需求关联起来，生成代码生成节点真正使用的提示词。该步骤是纯函数计算，
 * 不调用模型，因此稳定、可重复，也便于单元测试覆盖。</p>
 */
@Slf4j
@Component
public class PromptEnhancerNode {

    /**
     * 创建节点动作。
     *
     * @return LangGraph4j 节点动作
     */
    public AsyncNodeAction<MessagesState<String>> create() {
        return node_async(this::execute);
    }

    private Map<String, Object> execute(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.require(state);
        String enhancedPrompt = PromptEnhancer.enhance(context.getOriginalPrompt(), context.getImages());
        context.setEnhancedPrompt(enhancedPrompt);
        context.setCurrentStep("提示词增强完成");
        log.info("提示词增强节点完成，workflowId={}，原始长度={}，增强后长度={}，图片数={}",
                context.getWorkflowId(),
                context.getOriginalPrompt() == null ? 0 : context.getOriginalPrompt().length(),
                enhancedPrompt.length(),
                context.imageCount());
        return WorkflowState.save(context);
    }

    /**
     * 节点 id，供图定义和测试引用。
     *
     * @return 节点 id
     */
    public static String nodeId() {
        return WorkflowNodes.PROMPT_ENHANCER;
    }
}
