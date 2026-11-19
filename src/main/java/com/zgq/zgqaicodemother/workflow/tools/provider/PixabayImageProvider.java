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
 * Pixabay 内容图片渠道。
 *
 * <p>Pixabay 提供免费 API Key，接口简单、配额友好，是 Pexels 不可用时的首选替代渠道。
 * 图片地址使用 webformatURL，宽度足够网页展示，又不会像原图那样过大。</p>
 */
public final class PixabayImageProvider implements ContentImageProvider {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final HttpJsonClient httpClient;

    private final WorkflowProperties.ContentImage config;

    private final WorkflowProperties.Pixabay pixabay;

    public PixabayImageProvider(HttpJsonClient httpClient, WorkflowProperties.ContentImage config) {
        this.httpClient = httpClient;
        this.config = config;
        this.pixabay = config.getPixabay();
    }

    @Override
    public String name() {
        return "pixabay";
    }

    @Override
    public boolean available() {
        return pixabay != null && StrUtil.isNotBlank(pixabay.getApiKey()) && StrUtil.isNotBlank(pixabay.getBaseUrl());
    }

    @Override
    public List<ImageResource> search(String query, int limit) throws IOException {
        if (!available()) {
            return List.of();
        }
        URI uri = ProviderUris.withQuery(pixabay.getBaseUrl(), Map.of(
                "key", pixabay.getApiKey().trim(),
                "q", query,
                "image_type", "photo",
                "orientation", "horizontal",
                "safesearch", "true",
                "per_page", String.valueOf(limit),
                "page", "1"));
        return parse(httpClient.getJson(uri, Map.of("Accept", "application/json"),
                config.getRequestTimeout(), MAX_RESPONSE_BYTES), limit);
    }

    static List<ImageResource> parse(JSONObject json, int limit) {
        if (json == null) {
            return List.of();
        }
        JSONArray hits = json.getJSONArray("hits");
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ImageResource> images = new ArrayList<>();
        for (int i = 0; i < hits.size() && images.size() < limit; i++) {
            JSONObject hit = hits.getJSONObject(i);
            if (hit == null) {
                continue;
            }
            String url = hit.getStr("webformatURL");
            String description = hit.getStr("tags", "内容图片");
            ImageResource image = WorkflowImageSupport.create(
                    ImageCategoryEnum.CONTENT, description, url, "pixabay");
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }
}
