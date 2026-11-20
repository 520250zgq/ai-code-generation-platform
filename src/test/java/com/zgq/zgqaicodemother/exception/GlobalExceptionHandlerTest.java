package com.zgq.zgqaicodemother.exception;

import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.security.ratelimit.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void rateLimitExceededReturnsHttp429WithRetryAfter() {
        ResponseEntity<BaseResponse<?>> response = handler.rateLimitExceededHandler(
                new RateLimitExceededException("ai-chat", "ip", 42L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("42", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("频繁"));
    }

    @Test
    void rateLimitRetryAfterNeverBelowOneSecond() {
        ResponseEntity<BaseResponse<?>> response = handler.rateLimitExceededHandler(
                new RateLimitExceededException("ai-chat", "user", 0L));

        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void malformedJsonBodyIsReportedAsParameterError() {
        BaseResponse<?> response = handler.messageNotReadableHandler(
                new HttpMessageNotReadableException("Unexpected character", (HttpInputMessage) null));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("JSON"));
    }
}
