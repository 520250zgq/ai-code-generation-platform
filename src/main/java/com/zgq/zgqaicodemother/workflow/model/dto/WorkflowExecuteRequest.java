package com.zgq.zgqaicodemother.workflow.model.dto;

import lombok.Data;

/**
 * 工作流执行请求。
 *
 * <p>只允许客户端指定应用和提示词。生成目录、创建人、路由结果等都由服务端根据应用数据和
 * 工作流状态决定，不能由请求体覆盖。</p>
 */
@Data
public class WorkflowExecuteRequest {

    /**
     * 目标应用 id。
     */
    private Long appId;

    /**
     * 本次需求。为空时使用应用创建时的初始化提示词。
     */
    private String prompt;
}
