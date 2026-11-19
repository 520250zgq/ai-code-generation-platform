package com.zgq.zgqaicodemother.workflow.model;

import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流事件到 SSE JSON 的转换。
 *
 * <p>不使用框架的自动序列化，而是显式构造 Map：SSE 数据格式属于前端协议，字段改名或
 * 序列化器升级都可能破坏前端解析，集中在这个类里更容易测试和维护。</p>
 */
public final class WorkflowEventMapper {

    private WorkflowEventMapper() {
    }

    /**
     * 将事件转换为可直接 JSON 序列化的 Map。
     *
     * @param event 工作流事件
     * @return 字段稳定的 Map
     */
    public static Map<String, Object> toMap(WorkflowEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (event == null) {
            return data;
        }
        putIfNotNull(data, "type", event.type());
        putIfNotNull(data, "workflowId", event.workflowId());
        putIfNotNull(data, "node", event.node());
        putIfNotNull(data, "nodeName", event.nodeName());
        data.put("step", event.step());
        putIfNotNull(data, "message", event.message());
        data.put("elapsedMs", event.elapsedMs());
        putIfNotNull(data, "timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        if (event.result() != null) {
            data.put("result", resultToMap(event.result()));
        }
        return data;
    }

    /**
     * 将最终结果转换为 Map。
     *
     * @param result 工作流结果
     * @return 字段稳定的 Map
     */
    public static Map<String, Object> resultToMap(WorkflowResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (result == null) {
            return data;
        }
        putIfNotNull(data, "workflowId", result.workflowId());
        // 雪花 id 超过 JS Number 安全整数范围，SSE 中统一用字符串，和全局 Jackson 配置保持一致。
        putIfNotNull(data, "appId", result.appId() == null ? null : result.appId().toString());
        data.put("success", result.success());
        putIfNotNull(data, "currentStep", result.currentStep());
        putIfNotNull(data, "generationType", result.generationType());
        putIfNotNull(data, "generatedCodeDir", result.generatedCodeDir());
        putIfNotNull(data, "buildResultDir", result.buildResultDir());
        data.put("imageCount", result.imageCount());
        data.put("enhancedPromptLength", result.enhancedPromptLength());
        putIfNotNull(data, "errorMessage", result.errorMessage());
        data.put("elapsedMs", result.elapsedMs());
        data.put("warnings", result.warnings() == null ? List.of() : List.copyOf(result.warnings()));
        data.put("images", imagesToMaps(result.images()));
        return data;
    }

    private static List<Map<String, Object>> imagesToMaps(List<ImageResource> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(images.size());
        for (ImageResource image : images) {
            if (image == null) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            ImageCategoryEnum category = image.getCategory();
            putIfNotNull(data, "category", category == null ? null : category.getValue());
            putIfNotNull(data, "categoryText", image.categoryText());
            putIfNotNull(data, "description", image.getDescription());
            putIfNotNull(data, "url", image.getUrl());
            putIfNotNull(data, "source", image.getSource());
            result.add(data);
        }
        return result;
    }

    private static void putIfNotNull(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
