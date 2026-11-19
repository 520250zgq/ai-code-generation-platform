package com.zgq.zgqaicodemother.workflow.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTP 安全工具和响应大小限制测试。
 */
class WorkflowHttpSupportTest {

    @Test
    void requiresHttpsAndValidHost() {
        assertDoesNotThrow(() -> WorkflowHttpSupport.requireSafeHttpsUri(
                URI.create("https://cdn.example.com/a.png")));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowHttpSupport.requireSafeHttpsUri(URI.create("http://cdn.example.com/a.png")));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowHttpSupport.requireSafeHttpsUri(URI.create("https://user:pass@cdn.example.com/a.png")));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowHttpSupport.requireSafeHttpsUri(URI.create("https:///missing-host")));
    }

    @Test
    void matchesAllowedHostsOnDomainBoundary() {
        var allowed = java.util.Set.of("aliyuncs.com");
        assertDoesNotThrow(() -> WorkflowHttpSupport.requireAllowedHost(
                URI.create("https://dashscope-result.oss-cn-hangzhou.aliyuncs.com/a.png"), allowed));
        assertThrows(IllegalArgumentException.class, () -> WorkflowHttpSupport.requireAllowedHost(
                URI.create("https://evil-aliyuncs.com/a.png"), allowed));
        assertThrows(IllegalArgumentException.class, () -> WorkflowHttpSupport.requireAllowedHost(
                URI.create("https://aliyuncs.com.evil.com/a.png"), allowed));
    }

    @Test
    void rejectsHeaderInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowHttpSupport.requireSafeHeaderValue("value\r\nX-Evil: 1"));
        assertEquals("value", WorkflowHttpSupport.requireSafeHeaderValue("value"));
    }

    @Test
    void readsBodyUpToLimit() throws IOException {
        assertArrayEquals(new byte[]{1, 2, 3}, JdkHttpJsonClient.readLimited(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        assertThrows(WorkflowHttpException.class, () -> JdkHttpJsonClient.readLimited(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3));
    }
}
