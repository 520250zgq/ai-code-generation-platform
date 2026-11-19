package com.zgq.zgqaicodemother.workflow.http;

import java.io.IOException;

/**
 * 工作流外部 HTTP 请求异常。
 *
 * <p>异常消息只包含状态码和经过截断的响应片段，不包含请求头，避免 API Key 进入日志或
 * 返回给前端。</p>
 */
public class WorkflowHttpException extends IOException {

    /**
     * HTTP 状态码；网络层错误时为 0。
     */
    private final int statusCode;

    public WorkflowHttpException(String message) {
        this(0, message, null);
    }

    public WorkflowHttpException(String message, Throwable cause) {
        this(0, message, cause);
    }

    public WorkflowHttpException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public WorkflowHttpException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
