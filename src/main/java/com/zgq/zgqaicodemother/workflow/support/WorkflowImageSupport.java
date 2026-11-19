package com.zgq.zgqaicodemother.workflow.support;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图片素材的清洗与校验。
 *
 * <p>图片描述、搜索关键词都来自外部接口或模型，最终会拼进提示词。这里统一做长度限制、
 * 控制字符清理和 URL 安全校验，避免外部内容破坏提示词结构，或把内网地址、非 HTTP 协议
 * 写进生成出的网站。</p>
 */
public final class WorkflowImageSupport {

    /**
     * 图片描述的最大长度，足够表达内容，又不会让提示词被单条素材占满。
     */
    public static final int MAX_DESCRIPTION_LENGTH = 160;

    /**
     * 搜索关键词最大长度。
     */
    public static final int MAX_QUERY_LENGTH = 80;

    /**
     * 图片 URL 最大长度。
     */
    public static final int MAX_URL_LENGTH = 2048;

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "169.254.169.254",
            "metadata.google.internal");

    private WorkflowImageSupport() {
    }

    /**
     * 创建并校验图片素材。任何字段不合法时返回 null，由调用方跳过该条结果。
     *
     * @param category    图片类别
     * @param description 原始描述
     * @param url         原始 URL
     * @param source      素材来源
     * @return 清洗后的素材，不合法时返回 null
     */
    public static ImageResource create(ImageCategoryEnum category, String description,
                                       String url, String source) {
        if (category == null || !isSafePublicUrl(url)) {
            return null;
        }
        String safeDescription = sanitizeText(description, MAX_DESCRIPTION_LENGTH);
        if (StrUtil.isBlank(safeDescription)) {
            safeDescription = category.getText();
        }
        return ImageResource.builder()
                .category(category)
                .description(safeDescription)
                .url(url.trim())
                .source(sanitizeText(source, 40))
                .build();
    }

    /**
     * 清洗搜索关键词，返回 null 表示关键词不可用。
     *
     * @param query 原始关键词
     * @return 清洗后的关键词
     */
    public static String sanitizeQuery(String query) {
        String sanitized = sanitizeText(query, MAX_QUERY_LENGTH);
        return StrUtil.isBlank(sanitized) ? null : sanitized;
    }

    /**
     * 清理控制字符、压缩空白并截断到指定长度。
     *
     * @param raw       原始文本
     * @param maxLength 最大长度
     * @return 清理后的文本，raw 为 null 时返回 null
     */
    public static String sanitizeText(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(Math.min(raw.length(), maxLength));
        boolean lastWhitespace = false;
        for (int i = 0; i < raw.length() && builder.length() < maxLength; i++) {
            char current = raw.charAt(i);
            // 先处理空白：\n、\t 同时也是 ISO 控制字符，必须优先转换为单个空格，
            // 否则多行描述会被直接拼成一个词。
            if (Character.isWhitespace(current)) {
                if (!lastWhitespace && builder.length() > 0) {
                    builder.append(' ');
                    lastWhitespace = true;
                }
                continue;
            }
            if (Character.isISOControl(current)) {
                continue;
            }
            builder.append(current);
            lastWhitespace = false;
        }
        return builder.toString().trim();
    }

    /**
     * 校验图片地址：只允许公网 http/https，禁止用户信息、内网主机名和 IP 字面量。
     *
     * @param url 图片地址
     * @return 合法返回 true
     */
    public static boolean isSafePublicUrl(String url) {
        if (StrUtil.isBlank(url) || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        String trimmed = url.trim();
        if (containsControlCharacter(trimmed)) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getUserInfo() != null) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (BLOCKED_HOSTS.contains(lowerHost)
                    || lowerHost.endsWith(".localhost")
                    || lowerHost.endsWith(".local")
                    || lowerHost.endsWith(".internal")) {
                return false;
            }
            // 图片站点都应使用域名；拒绝 IP 字面量可以显著降低 SSRF 风险。
            return !isIpLiteral(lowerHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 按 URL 去重并限制总数，保持输入顺序。
     *
     * @param images   原始列表
     * @param maxTotal 最大数量
     * @return 去重后的列表，输入为 null 时返回空列表
     */
    public static List<ImageResource> dedupeAndCap(List<ImageResource> images, int maxTotal) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        int limit = maxTotal <= 0 ? images.size() : maxTotal;
        Set<String> seen = new LinkedHashSet<>();
        List<ImageResource> result = new ArrayList<>(Math.min(limit, images.size()));
        for (ImageResource image : images) {
            if (image == null || !isSafePublicUrl(image.getUrl())) {
                continue;
            }
            if (seen.add(image.getUrl())) {
                result.add(image);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\r' || current == '\n' || current == '\0' || Character.isISOControl(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断主机名是否为 IPv4 / IPv6 字面量。IPv6 在 URI 中可能带方括号，这里一并处理。
     */
    private static boolean isIpLiteral(String host) {
        String normalized = host;
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.contains(":")) {
            return true;
        }
        return normalized.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
