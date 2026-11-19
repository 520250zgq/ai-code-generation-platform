package com.zgq.zgqaicodemother.common;

import com.zgq.zgqaicodemother.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一接口响应体，包含业务状态码、数据和提示信息。
 */
@Data
public class BaseResponse<T> implements Serializable {

    private int code;

    private T data;

    private String message;

    /**
     * 构造带业务码、数据和提示语的响应。
     */
    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    /**
     * 构造不带额外提示语的响应。
     */
    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    /**
     * 根据错误码构造失败响应。
     */
    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
