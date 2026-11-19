package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 智能路由工作节点。
 *
 * <p>使用用户原始需求做分类，而不是塞满图片清单的增强提示词：图片 URL 和技术细节会干扰模型
 * 判断网站复杂度。路由失败时回退到 HTML 模式，保证工作流继续执行。</p>
 */
@Slf4j
@Component
public class RouterNode {

    /**
     * 路由失败时的兜底类型。HTML 模式输出成本最低，且所有模型都支持。
     */
    static final CodeGenTypeEnum FALLBACK_TYPE = CodeGenTypeEnum.HTML;

    private final AiCodeGenTypeRoutingService routingService;

    public RouterNode(AiCodeGenTypeRoutingService routingService) {
        this.routingService = routingService;
    }

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
        CodeGenTypeEnum type = null;
        try {
            type = routingService.routeCodeGenType(context.getOriginalPrompt());
        } catch (Exception e) {
            log.warn("智能路由失败，回退到 {} 模式，workflowId={}，原因={}",
                    FALLBACK_TYPE.getValue(), context.getWorkflowId(), e.getMessage());
            context.addWarning("智能路由失败，已回退到原生 HTML 模式");
        }
        if (type == null) {
            type = FALLBACK_TYPE;
            context.addWarning("智能路由未返回有效类型，已回退到原生 HTML 模式");
        }
        context.setGenerationType(type);
        context.setCurrentStep("智能路由：" + type.getText());
        log.info("智能路由节点完成，workflowId={}，生成类型={}", context.getWorkflowId(), type.getValue());
        return WorkflowState.save(context);
    }

    /**
     * 节点 id，供图定义和测试引用。
     *
     * @return 节点 id
     */
    public static String nodeId() {
        return WorkflowNodes.ROUTER;
    }
}
