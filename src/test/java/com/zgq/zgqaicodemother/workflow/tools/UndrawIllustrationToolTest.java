package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * unDraw 页面解析和工具调用测试。
 */
class UndrawIllustrationToolTest {

    private static final String VALID_HTML = """
            <!doctype html><html><head></head><body>
            <script id="__NEXT_DATA__" type="application/json">%s</script>
            </body></html>
            """.formatted(JSONUtil.createObj()
            .set("props", JSONUtil.createObj()
                    .set("pageProps", JSONUtil.createObj()
                            .set("initialResults", JSONUtil.createArray()
                                    .set(JSONUtil.createObj()
                                            .set("title", "Team work")
                                            .set("media", "https://cdn.undraw.co/illustration/team.svg"))
                                    .set(JSONUtil.createObj()
                                            .set("title", "Data")
                                            .set("media", "https://cdn.undraw.co/illustration/data.svg")))))
            .toString());

    @Test
    void parsesNextDataResultsAndRespectsLimit() {
        List<ImageResource> images = UndrawIllustrationTool.parseIllustrations(VALID_HTML, "cdn.undraw.co", 1);

        assertEquals(1, images.size());
        assertEquals("Team work", images.get(0).getDescription());
        assertEquals("https://cdn.undraw.co/illustration/team.svg", images.get(0).getUrl());
        assertEquals("undraw", images.get(0).getSource());
    }

    @Test
    void rejectsImagesFromOtherHosts() {
        String html = """
                <script id="__NEXT_DATA__" type="application/json">%s</script>
                """.formatted(JSONUtil.createObj()
                .set("props", JSONUtil.createObj()
                        .set("pageProps", JSONUtil.createObj()
                                .set("initialResults", JSONUtil.createArray()
                                        .set(JSONUtil.createObj()
                                                .set("title", "Evil")
                                                .set("media", "https://evil.example.com/team.svg")))))
                .toString());

        assertTrue(UndrawIllustrationTool.parseIllustrations(html, "cdn.undraw.co", 4).isEmpty());
    }

    @Test
    void fallsBackToRegexWhenNextDataMissing() {
        String html = "<div data-x=\"1\" \"media\":\"https://cdn.undraw.co/illustration/fallback.svg\"></div>";

        List<ImageResource> images = UndrawIllustrationTool.parseIllustrations(html, "cdn.undraw.co", 4);

        assertEquals(1, images.size());
        assertEquals("https://cdn.undraw.co/illustration/fallback.svg", images.get(0).getUrl());
    }

    @Test
    void buildsEncodedSearchUrlAndCallsHttpClient() throws Exception {
        HttpJsonClient client = mock(HttpJsonClient.class);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getUndraw().setPerPage(2);
        when(client.getText(any(URI.class), anyMap(), any(), anyInt())).thenReturn(VALID_HTML);
        UndrawIllustrationTool tool = new UndrawIllustrationTool(client, properties);

        List<ImageResource> images = tool.searchIllustrations("team work");

        assertEquals(2, images.size());
        verify(client).getText(any(URI.class), anyMap(), any(), anyInt());
        assertTrue(tool.available());
    }

    @Test
    void unavailableWhenDisabled() {
        WorkflowProperties properties = new WorkflowProperties();
        properties.getUndraw().setEnabled(false);
        UndrawIllustrationTool tool = new UndrawIllustrationTool(mock(HttpJsonClient.class), properties);

        assertFalse(tool.available());
        assertTrue(tool.searchIllustrations("team").isEmpty());
    }
}
