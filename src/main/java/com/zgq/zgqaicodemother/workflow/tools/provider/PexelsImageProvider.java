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
 * Pexels 内容图片渠道。
 *
 * <p>Pexels 的搜索接口使用 Authorization 头鉴权。部分账号暂时无法申请 API Key，因此该渠道
 * 未配置时直接标记为不可用，由搜索工具自动降级到其他渠道。</p>
 */
public final class PexelsImageProvider implements ContentImageProvider {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final HttpJsonClient httpClient;

    private final WorkflowProperties.ContentImage config;

    private final WorkflowProperties.Pexels pexels;

    public PexelsImageProvider(HttpJsonClient httpClient, WorkflowProperties.ContentImage config) {
        this.httpClient = httpClient;
        this.config = config;
        this.pexels = config.getPexels();
    }

    @Override
    public String name() {
        return "pexels";
    }

    @Override
    public boolean available() {
        return pexels != null && StrUtil.isNotBlank(pexels.getApiKey()) && StrUtil.isNotBlank(pexels.getBaseUrl());
    }

    @Override
    public List<ImageResource> search(String query, int limit) throws IOException {
        if (!available()) {
            return List.of();
        }
        URI uri = ProviderUris.withQuery(pexels.getBaseUrl(), Map.of(
                "query", query,
                "per_page", String.valueOf(limit),
                "page", "1"));
        Map<String, String> headers = Map.of(
                "Authorization", pexels.getApiKey().trim(),
                "Accept", "application/json");
        return parse(httpClient.getJson(uri, headers, config.getRequestTimeout(), MAX_RESPONSE_BYTES), limit);
    }

    static List<ImageResource> parse(JSONObject json, int limit) {
        if (json == null) {
            return List.of();
        }
        JSONArray photos = json.getJSONArray("photos");
        if (photos == null || photos.isEmpty()) {
            return List.of();
        }
        List<ImageResource> images = new ArrayList<>();
        for (int i = 0; i < photos.size() && images.size() < limit; i++) {
            JSONObject photo = photos.getJSONObject(i);
            if (photo == null) {
                continue;
            }
            JSONObject src = photo.getJSONObject("src");
            String url = src == null ? null : src.getStr("medium");
            String description = photo.getStr("alt", "内容图片");
            ImageResource image = WorkflowImageSupport.create(
                    ImageCategoryEnum.CONTENT,
                    description,
                    url,
                    "pexels");
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }
}
