package com.zgq.zgqaicodemother.workflow.tools.provider;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 免密钥占位图片渠道。
 *
 * <p>当 Pexels / Pixabay / Unsplash 都没有配置 Key 时，使用 Picsum 的固定 seed 地址返回
 * 真实可访问的图片。它不能保证与需求语义相关，但能保证生成的网站有可用素材，且不会因为
 * 缺少第三方密钥导致整个工作流失败。生成的 URL 只用于演示，生产环境应配置正式图片渠道。</p>
 */
public final class PicsumImageProvider implements ContentImageProvider {

    private static final int IMAGE_WIDTH = 800;

    private static final int IMAGE_HEIGHT = 600;

    private final WorkflowProperties.Fallback fallback;

    public PicsumImageProvider(WorkflowProperties.Fallback fallback) {
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return "picsum";
    }

    @Override
    public boolean available() {
        return fallback != null && fallback.isEnabled() && StrUtil.isNotBlank(fallback.getBaseUrl());
    }

    @Override
    public List<ImageResource> search(String query, int limit) throws IOException {
        if (!available()) {
            return List.of();
        }
        int actualLimit = Math.max(1, Math.min(limit, 8));
        String baseUrl = fallback.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // 用关键词哈希生成稳定 seed，同一关键词每次得到同一批图片，避免重复生成时页面乱跳。
        String seedBase = Integer.toHexString(query.hashCode());
        List<ImageResource> images = new ArrayList<>(actualLimit);
        for (int i = 0; i < actualLimit; i++) {
            String url = baseUrl + "/" + seedBase + "-" + i
                    + "/" + IMAGE_WIDTH + "/" + IMAGE_HEIGHT;
            ImageResource image = WorkflowImageSupport.create(
                    ImageCategoryEnum.CONTENT,
                    query + " 场景配图",
                    url,
                    "picsum");
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }
}
