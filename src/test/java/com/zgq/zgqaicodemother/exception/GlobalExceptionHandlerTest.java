package com.zgq.zgqaicodemother.exception;

import com.zgq.zgqaicodemother.common.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void redisConnectionFailureReturnsActionableMessage() {
        BaseResponse<?> response = handler.redisConnectionFailureHandler(
                new RedisConnectionFailureException("Connection refused"));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("Redis"));
        assertTrue(response.getMessage().contains("6379"));
    }
}
