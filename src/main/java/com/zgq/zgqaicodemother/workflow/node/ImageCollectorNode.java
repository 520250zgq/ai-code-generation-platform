package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.service.ImageCollectionService;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 图片收集工作节点。
 *
 * <p>节点本身只负责状态读写，把“规划 + 并发调用工具”交给 {@link ImageCollectionService}。
 * 图片收集失败属于非致命问题：记录告警、保留空列表，继续执行后面的提示词增强和代码生成。</p>
 */
@Slf4j
@Component
public class ImageCollectorNode {

    private final ImageCollectionService imageCollectionService;

    public ImageCollectorNode(ImageCollectionService imageCollectionService) {
        this.imageCollectionService = imageCollectionService;
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
        context.setCurrentStep("图片收集中");
        try {
            ImageCollectionService.ImageCollectionOutcome outcome =
                    imageCollectionService.collect(context.getOriginalPrompt());
            context.setImageCollectionPlan(outcome.plan());
            context.setImages(outcome.images());
            outcome.warnings().forEach(context::addWarning);
            context.setCurrentStep("图片收集完成（" + context.imageCount() + " 张）");
            log.info("图片收集节点完成，workflowId={}，appId={}，images={}，warnings={}",
                    context.getWorkflowId(), context.getAppId(), context.imageCount(),
                    context.getWarnings().size());
        } catch (Exception e) {
            // 服务内部已经做了大部分兜底；这里是最后一道防线，图片失败不影响主流程。
            context.setImages(java.util.List.of());
            context.addWarning("图片收集节点异常：" + e.getMessage());
            context.setCurrentStep("图片收集失败，继续执行");
            log.warn("图片收集节点异常，workflowId={}", context.getWorkflowId(), e);
        }
        return WorkflowState.save(context);
    }

    /**
     * 节点 id，供图定义和测试引用。
     *
     * @return 节点 id
     */
    public static String nodeId() {
        return WorkflowNodes.IMAGE_COLLECTOR;
    }
}
