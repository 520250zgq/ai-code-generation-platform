package com.zgq.zgqaicodemother.workflow.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 事件字段稳定性测试。
 */
class WorkflowEventMapperTest {

    @Test
    void serializesAppIdAsStringToAvoidJavaScriptPrecisionLoss() {
        WorkflowResult result = new WorkflowResult(
                "wf-1", 480506049355333632L, true, "完成", "html",
                "html_480506049355333632", "html_480506049355333632", 0, 10,
                List.of(), List.of("warning"), null,
                QualityCheckResult.builder()
                        .valid(true)
                        .errors(List.of())
                        .suggestions(List.of())
                        .checkedFiles(List.of("index.html"))
                        .checkedChars(120)
                        .source("static+ai")
                        .build(),
                1, 20L);
        WorkflowEvent event = WorkflowEvent.completed("wf-1", "完成", 20L, result);

        Map<String, Object> data = WorkflowEventMapper.toMap(event);
        @SuppressWarnings("unchecked")
        Map<String, Object> mappedResult = (Map<String, Object>) data.get("result");

        assertEquals("480506049355333632", mappedResult.get("appId"));
        assertEquals("workflow_completed", data.get("type"));
        assertTrue(data.containsKey("timestamp"));
        assertFalse(data.containsKey("enhancedPrompt"));
        assertEquals(1, mappedResult.get("repairAttempts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> qualityCheck = (Map<String, Object>) mappedResult.get("qualityCheck");
        assertEquals(true, qualityCheck.get("valid"));
        assertEquals(List.of("index.html"), qualityCheck.get("checkedFiles"));
        assertEquals(120, qualityCheck.get("checkedChars"));
    }
}
