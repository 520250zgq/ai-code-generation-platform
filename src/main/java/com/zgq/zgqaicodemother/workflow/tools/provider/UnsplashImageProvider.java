package com.zgq.zgqaicodemother.workflow.tools.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unsplash 内容图片渠道。
 *
 * <p>使用官方 Search Photos 接口，Access Key 通过 client_id 查询参数传递。返回的 regular
 * 尺寸适合网页卡片展示，避免直接引用原图导致页面加载过慢。</p>
 */
public final class UnsplashImageProvider implements ContentImageProvider {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final HttpJsonClient httpClient;

    private final WorkflowProperties.ContentImage config;

    private final WorkflowProperties.Unsplash unsplash;

    public UnsplashImageProvider(HttpJsonClient httpClient, WorkflowProperties.ContentImage config) {
        this.httpClient = httpClient;
        this.config = config;
        this.unsplash = config.getUnsplash();
    }

    @Override
    public String name() {
        return "unsplash";
    }

    @Override
    public boolean available() {
        return unsplash != null
                && StrUtil.isNotBlank(unsplash.getAccessKey())
                && StrUtil.isNotBlank(unsplash.getBaseUrl());
    }

    @Override
    public List<ImageResource> search(String query, int limit) throws IOException {
        if (!available()) {
            return List.of();
        }
        URI uri = ProviderUris.withQuery(unsplash.getBaseUrl(), Map.of(
                "client_id", unsplash.getAccessKey().trim(),
                "query", query,
                "per_page", String.valueOf(limit),
                "page", "1",
                "orientation", "landscape",
                "content_filter", "high"));
        return parse(httpClient.getJson(uri, Map.of("Accept", "application/json"),
                config.getRequestTimeout(), MAX_RESPONSE_BYTES), limit);
    }

    static List<ImageResource> parse(JSONObject json, int limit) {
        if (json == null) {
            return List.of();
        }
        JSONArray results = json.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<ImageResource> images = new ArrayList<>();
        for (int i = 0; i < results.size() && images.size() < limit; i++) {
            JSONObject result = results.getJSONObject(i);
            if (result == null) {
                continue;
            }
            JSONObject urls = result.getJSONObject("urls");
            String url = urls == null ? null : urls.getStr("regular");
            String description = result.getStr("alt_description", "内容图片");
            ImageResource image = WorkflowImageSupport.create(
                    ImageCategoryEnum.CONTENT, description, url, "unsplash");
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }
}
