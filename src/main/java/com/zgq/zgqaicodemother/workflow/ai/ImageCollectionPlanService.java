package com.zgq.zgqaicodemother.workflow.ai;

import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 图片收集规划 AI 服务。
 *
 * <p>只做一次结构化规划，不绑定对话记忆：每次工作流独立分析需求，避免不同应用之间共享上下文。
 * 模型返回的是 {@link ImageCollectionPlan}，由 LangChain4j 按 JSON 结构反序列化。</p>
 */
public interface ImageCollectionPlanService {

    /**
     * 根据用户需求制定图片收集计划。
     *
     * @param userPrompt 用户原始需求
     * @return 图片收集计划，模型返回非法内容时由框架抛出解析异常
     */
    @SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
    ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
}
