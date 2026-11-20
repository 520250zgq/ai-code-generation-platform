package com.zgq.zgqaicodemother.workflow.ai;

import com.zgq.zgqaicodemother.workflow.model.QualityCheckReview;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 代码质检 AI 服务。
 *
 * <p>只做一次结构化复核，不绑定对话记忆：每次质检独立分析当前代码，避免不同应用
 * 或不同修复轮次之间共享上下文。模型返回 {@link QualityCheckReview}，由 LangChain4j
 * 按 JSON 结构反序列化。</p>
 */
public interface CodeQualityCheckService {

    /**
     * 复核一次生成的代码。
     *
     * @param codeReviewRequest 包含生成模式、用户需求、文件清单和代码内容的复核请求
     * @return 结构化复核结果，模型返回非法内容时由框架抛出解析异常
     */
    @SystemMessage(fromResource = "prompt/code-quality-check-system-prompt.txt")
    QualityCheckReview reviewCode(@UserMessage String codeReviewRequest);
}
