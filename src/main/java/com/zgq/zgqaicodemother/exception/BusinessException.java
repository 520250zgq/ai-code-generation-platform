package com.zgq.zgqaicodemother.exception;

import lombok.Getter;

/**
 * 业务异常，携带面向前端的业务错误码和可读提示。
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 错误码
     */
    private final int code;

    /**
     * 使用自定义错误码和提示构造异常。
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用预定义错误码的默认提示构造异常。
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 使用预定义错误码并覆盖提示语构造异常。
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}

