package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.DownloadedResource;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Logo 文生图工具的异步轮询、转存和失败降级测试。
 */
class LogoGeneratorToolTest {

    @Test
    void pollsTaskAndReturnsGeneratedImage() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        CosManager cosManager = mock(CosManager.class);
        WorkflowProperties properties = logoProperties(false);
        when(client.postJson(any(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                        {"output":{"task_id":"task-1","task_status":"PENDING"}}
                        """));
        when(client.getJson(any(), anyMap(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                                {"output":{"task_id":"task-1","task_status":"RUNNING"}}
                                """))
                .thenReturn(JSONUtil.parseObj("""
                        {"output":{"task_id":"task-1","task_status":"SUCCEEDED",
                         "results":[{"url":"https://dashscope-result.oss-cn-hangzhou.aliyuncs.com/logo.png"}]}}
                        """));
        LogoGeneratorTool tool = new LogoGeneratorTool(client, cosManager, properties);

        List<ImageResource> images = tool.generateLogos("云舟科技，蓝紫渐变");

        assertEquals(1, images.size());
        assertTrue(images.get(0).getUrl().startsWith("https://dashscope-result."));
        assertEquals("dashscope", images.get(0).getSource());
        verify(client).postJson(any(), anyMap(), anyString(), any(), anyInt());
        verify(cosManager, never()).uploadWorkflowImage(any(), anyString());
    }

    @Test
    void transfersImageToCosWhenEnabled() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        CosManager cosManager = mock(CosManager.class);
        WorkflowProperties properties = logoProperties(true);
        when(cosManager.isAvailable()).thenReturn(true);
        when(client.postJson(any(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                        {"output":{"task_id":"task-2"}}
                        """));
        when(client.getJson(any(), anyMap(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                        {"output":{"task_status":"SUCCEEDED",
                         "results":[{"url":"https://dashscope-result.oss-cn-hangzhou.aliyuncs.com/logo.png"}]}}
                        """));
        when(client.download(any(URI.class), any(), any(Duration.class), anyInt()))
                .thenReturn(new DownloadedResource(new byte[]{1, 2, 3}, "image/png",
                        URI.create("https://dashscope-result.oss-cn-hangzhou.aliyuncs.com/logo.png")));
        when(cosManager.uploadWorkflowImage(any(), anyString()))
                .thenReturn("https://cdn.example.com/workflow/logos/logo.png");
        LogoGeneratorTool tool = new LogoGeneratorTool(client, cosManager, properties);

        List<ImageResource> images = tool.generateLogos("云舟科技");

        assertEquals(1, images.size());
        assertEquals("https://cdn.example.com/workflow/logos/logo.png", images.get(0).getUrl());
        verify(cosManager).uploadWorkflowImage(any(), anyString());
    }

    @Test
    void failedTaskReturnsEmpty() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = logoProperties(false);
        when(client.postJson(any(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                        {"output":{"task_id":"task-3"}}
                        """));
        when(client.getJson(any(), anyMap(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                        {"output":{"task_status":"FAILED","code":"InvalidParameter","message":"bad prompt"}}
                        """));
        LogoGeneratorTool tool = new LogoGeneratorTool(client, mock(CosManager.class), properties);

        assertTrue(tool.generateLogos("描述").isEmpty());
    }

    @Test
    void apiErrorReturnsEmpty() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = logoProperties(false);
        when(client.postJson(any(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(JSONUtil.parseObj("""
                        {"code":"InvalidApiKey","message":"invalid key"}
                        """));

        assertTrue(new LogoGeneratorTool(client, mock(CosManager.class), properties)
                .generateLogos("描述").isEmpty());
    }

    @Test
    void missingKeySkipsNetworkCalls() {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getLogo().setApiKey("");

        LogoGeneratorTool tool = new LogoGeneratorTool(client, mock(CosManager.class), properties);

        assertFalse(tool.available());
        assertTrue(tool.generateLogos("描述").isEmpty());
        verifyNoInteractions(client);
    }

    private static WorkflowProperties logoProperties(boolean storeToCos) {
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowProperties.Logo logo = properties.getLogo();
        logo.setEnabled(true);
        logo.setApiKey("test-dashscope-key");
        logo.setStoreToCos(storeToCos);
        logo.setPollInterval(Duration.ofMillis(1));
        logo.setMaxPollAttempts(3);
        logo.setTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
