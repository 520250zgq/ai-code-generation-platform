package com.zgq.zgqaicodemother.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    /**
     * 输入未通过提示词安全检查。与普通参数错误区分开，便于前端提示用户修改需求而不是重试。
     */
    PROMPT_REJECTED(40002, "输入内容未通过安全检查"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    /**
     * 触发限流。HTTP 状态同样返回 429，并携带 Retry-After 头，客户端据此退避。
     */
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后重试"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    /**
     * AI 生成并发已满或同一应用正在生成。与系统错误区分，前端可以直接提示稍后重试。
     */
    SERVICE_BUSY(50300, "AI 服务繁忙，请稍后重试");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

}
