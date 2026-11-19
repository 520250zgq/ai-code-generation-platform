package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * unDraw 插画收集工具。
 *
 * <p>unDraw 没有公开的搜索 API，搜索页是 Next.js 应用。这里不依赖容易变化的
 * {@code _next/data/{buildId}} 地址，而是直接读取页面中的 {@code __NEXT_DATA__} JSON，
 * 从中取出插画标题和 CDN 地址；如果页面结构调整导致解析失败，再退回正则提取媒体地址。</p>
 *
 * <p>只接受配置的 CDN 域名，避免页面内容被篡改后把任意地址注入提示词。</p>
 */
@Slf4j
@Component
public class UndrawIllustrationTool {

    private static final int MAX_PAGE_BYTES = 3 * 1024 * 1024;

    private static final Pattern NEXT_DATA_PATTERN = Pattern.compile(
            "(?is)<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>");

    private static final Pattern MEDIA_PATTERN = Pattern.compile(
            "(?i)\"media\"\\s*:\\s*\"(https://[^\"]+)\"");

    private final HttpJsonClient httpClient;

    private final WorkflowProperties.Undraw config;

    public UndrawIllustrationTool(HttpJsonClient httpClient, WorkflowProperties properties) {
        this.httpClient = httpClient;
        this.config = properties.getUndraw();
    }

    /**
     * 搜索插画图片。
     *
     * @param query 搜索关键词
     * @return 插画列表，未启用或解析失败时返回空列表
     */
    @Tool("搜索 unDraw 插画图片，用于网站美化和装饰；query 是搜索关键词")
    public List<ImageResource> searchIllustrations(@P("插画搜索关键词，例如 团队协作、数据分析") String query) {
        String safeQuery = WorkflowImageSupport.sanitizeQuery(query);
        if (safeQuery == null || !config.isEnabled()) {
            return List.of();
        }
        try {
            String url = buildSearchUrl(safeQuery);
            String html = httpClient.getText(URI.create(url), java.util.Map.of(
                    "Accept", "text/html,application/xhtml+xml",
                    "Accept-Language", "en-US,en;q=0.9"),
                    config.getRequestTimeout(), MAX_PAGE_BYTES);
            List<ImageResource> images = parseIllustrations(html, config.getCdnHost(), config.getPerPage());
            log.info("unDraw 插画搜索完成，limit={}，count={}", config.getPerPage(), images.size());
            return images;
        } catch (Exception e) {
            // unDraw 页面结构随时可能变化，失败时只记录日志，工作流继续执行。
            log.warn("unDraw 插画搜索失败：{}", e.getMessage());
            log.debug("unDraw 插画搜索失败详情", e);
            return List.of();
        }
    }

    /**
     * 判断 unDraw 渠道是否启用。
     *
     * @return 启用返回 true
     */
    public boolean available() {
        return config.isEnabled() && StrUtil.isNotBlank(config.getBaseUrl());
    }

    private String buildSearchUrl(String query) {
        String baseUrl = config.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        return baseUrl + "/search/" + encoded;
    }

    /**
     * 从搜索页 HTML 中解析插画。
     *
     * @param html    页面 HTML
     * @param cdnHost 允许的 CDN 域名
     * @param limit   最大数量
     * @return 插画列表
     */
    static List<ImageResource> parseIllustrations(String html, String cdnHost, int limit) {
        if (StrUtil.isBlank(html)) {
            return List.of();
        }
        int actualLimit = Math.max(1, Math.min(limit, 12));
        List<ImageResource> images = parseFromNextData(html, cdnHost, actualLimit);
        if (!images.isEmpty()) {
            return images;
        }
        return parseFallbackRegex(html, cdnHost, actualLimit);
    }

    private static List<ImageResource> parseFromNextData(String html, String cdnHost, int limit) {
        Matcher matcher = NEXT_DATA_PATTERN.matcher(html);
        if (!matcher.find()) {
            return List.of();
        }
        try {
            JSONObject root = JSONUtil.parseObj(matcher.group(1));
            JSONObject props = root.getJSONObject("props");
            if (props == null) {
                return List.of();
            }
            JSONObject pageProps = props.getJSONObject("pageProps");
            if (pageProps == null) {
                return List.of();
            }
            JSONArray results = pageProps.getJSONArray("initialResults");
            if (results == null || results.isEmpty()) {
                return List.of();
            }
            List<ImageResource> images = new ArrayList<>();
            for (int i = 0; i < results.size() && images.size() < limit; i++) {
                JSONObject result = results.getJSONObject(i);
                if (result == null) {
                    continue;
                }
                String media = result.getStr("media");
                String title = result.getStr("title", "插画");
                ImageResource image = toImage(media, title, cdnHost);
                if (image != null) {
                    images.add(image);
                }
            }
            return images;
        } catch (Exception e) {
            log.debug("解析 unDraw __NEXT_DATA__ 失败", e);
            return List.of();
        }
    }

    private static List<ImageResource> parseFallbackRegex(String html, String cdnHost, int limit) {
        Matcher matcher = MEDIA_PATTERN.matcher(html);
        List<ImageResource> images = new ArrayList<>();
        while (matcher.find() && images.size() < limit) {
            String media = matcher.group(1).replace("\\/", "/");
            ImageResource image = toImage(media, "插画", cdnHost);
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    private static ImageResource toImage(String media, String title, String cdnHost) {
        if (StrUtil.isBlank(media) || StrUtil.isBlank(cdnHost)) {
            return null;
        }
        try {
            URI uri = URI.create(media.trim());
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String allowedHost = cdnHost.trim().toLowerCase(Locale.ROOT);
            if (!normalizedHost.equals(allowedHost) && !normalizedHost.endsWith("." + allowedHost)) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return WorkflowImageSupport.create(ImageCategoryEnum.ILLUSTRATION, title, media, "undraw");
    }
}
