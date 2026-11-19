package com.zgq.zgqaicodemother.exception;

import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一捕获业务异常和运行时异常，转换为前端可识别的 BaseResponse。
 */
@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 返回业务异常携带的错误码和可读提示，不向客户端暴露堆栈。
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * Redis 是登录态和对话记忆的必需依赖；连接失败时返回可操作的提示，
     * 避免用户只看到笼统的“系统错误”。
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public BaseResponse<?> redisConnectionFailureHandler(RedisConnectionFailureException e) {
        log.error("Redis 连接失败，请确认 Redis 已启动并检查 spring.data.redis 配置", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR,
                "Redis 连接失败，请确认 Redis 已启动（默认 localhost:6379）");
    }

    /**
     * 兜底处理未预期异常，避免原始异常信息直接返回给客户端。
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
