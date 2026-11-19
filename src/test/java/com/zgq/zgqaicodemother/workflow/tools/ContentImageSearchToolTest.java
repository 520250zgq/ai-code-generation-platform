package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 内容图片搜索工具的渠道选择、解析和降级测试。
 */
class ContentImageSearchToolTest {

    @Test
    void parsesPexelsResponseAndSendsApiKey() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getContentImage().setProvider("pexels");
        properties.getContentImage().setPerPage(2);
        properties.getContentImage().getPexels().setApiKey("test-key");
        when(client.getJson(any(), anyMap(), any(Duration.class), anyInt())).thenReturn(JSONUtil.parseObj("""
                {
                  "photos": [
                    {"alt": "office team", "src": {"medium": "https://images.pexels.com/photos/1/a.jpg"}},
                    {"alt": "laptop", "src": {"medium": "https://images.pexels.com/photos/2/b.jpg"}},
                    {"alt": "extra", "src": {"medium": "https://images.pexels.com/photos/3/c.jpg"}}
                  ]
                }
                """));

        List<ImageResource> images = new ContentImageSearchTool(client, properties)
                .searchContentImages("office team");

        assertEquals(2, images.size());
        assertEquals("office team", images.get(0).getDescription());
        assertEquals("pexels", images.get(0).getSource());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(client).getJson(uriCaptor.capture(), headersCaptor.capture(), any(Duration.class), anyInt());
        assertEquals("test-key", headersCaptor.getValue().get("Authorization"));
        assertTrue(uriCaptor.getValue().toString().contains("query=office+team")
                        || uriCaptor.getValue().toString().contains("query=office%20team"),
                uriCaptor.getValue().toString());
    }

    @Test
    void fallsBackToPicsumWhenNoProviderConfigured() {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getContentImage().setPerPage(2);

        List<ImageResource> images = new ContentImageSearchTool(client, properties)
                .searchContentImages("technology");

        assertEquals(2, images.size());
        assertTrue(images.get(0).getUrl().startsWith("https://picsum.photos/seed/"));
        assertEquals("picsum", images.get(0).getSource());
        verifyNoInteractions(client);
    }

    @Test
    void fallsBackWhenConfiguredProviderFails() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getContentImage().setProvider("pexels");
        properties.getContentImage().getPexels().setApiKey("bad-key");
        when(client.getJson(any(), anyMap(), any(Duration.class), anyInt()))
                .thenThrow(new IOException("HTTP 401"));

        List<ImageResource> images = new ContentImageSearchTool(client, properties)
                .searchContentImages("office");

        assertFalse(images.isEmpty());
        assertEquals("picsum", images.get(0).getSource());
    }

    @Test
    void blankQueryDoesNotCallHttpClient() {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = new WorkflowProperties();

        List<ImageResource> images = new ContentImageSearchTool(client, properties)
                .searchContentImages(" \u0000 ");

        assertTrue(images.isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void reportsProviderAvailabilityWithoutSecrets() {
        WorkflowProperties properties = new WorkflowProperties();
        properties.getContentImage().getPixabay().setApiKey("key");

        Map<String, Boolean> availability = new ContentImageSearchTool(mock(HttpJsonClient.class), properties)
                .describeAvailability();

        assertTrue(availability.containsKey("pixabay"));
        assertTrue(availability.get("pixabay"));
        assertFalse(availability.get("pexels"));
        assertFalse(availability.toString().contains("key"));
    }
}
