package com.zgq.zgqaicodemother.workflow.service;

import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.workflow.model.WorkflowEvent;
import com.zgq.zgqaicodemother.workflow.model.WorkflowResult;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * AI 工作流服务。
 *
 * <p>工作流执行需要应用 id、用户需求和登录用户：服务层负责应用归属校验、并发控制、
 * 状态初始化和最终结果转换，控制器只处理 HTTP 协议。</p>
 */
public interface WorkflowService {

    /**
     * 同步执行工作流。
     *
     * <p>同步模式会阻塞调用线程直到图片收集、代码生成和项目构建全部完成，适合测试和后台任务；
     * 前端交互应使用 {@link #executeStream} 获取实时进度。</p>
     *
     * @param appId     目标应用 id
     * @param prompt    用户需求，为空时使用应用初始化提示词
     * @param loginUser 当前登录用户，必须是应用创建者
     * @return 工作流最终结果
     */
    WorkflowResult execute(Long appId, String prompt, User loginUser);

    /**
     * 流式执行工作流，按节点完成顺序推送事件，最后推送 workflow_completed 或 workflow_error。
     *
     * @param appId     目标应用 id
     * @param prompt    用户需求，为空时使用应用初始化提示词
     * @param loginUser 当前登录用户，必须是应用创建者
     * @return 工作流事件流
     */
    Flux<WorkflowEvent> executeStream(Long appId, String prompt, User loginUser);

    /**
     * 获取工作流图的 Mermaid 文本，便于前端展示流程。
     *
     * @return Mermaid 文本
     */
    String mermaidGraph();

    /**
     * 获取工作流相关能力的状态，不包含任何密钥。
     *
     * @return 状态信息
     */
    Map<String, Object> status();
}
