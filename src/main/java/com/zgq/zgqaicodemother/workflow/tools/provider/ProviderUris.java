package com.zgq.zgqaicodemother.workflow.tools.provider;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片渠道共用的 URL 构造工具。
 */
final class ProviderUris {

    private ProviderUris() {
    }

    /**
     * 在基础地址后追加查询参数。参数值统一 URL 编码，避免关键词中的空格、& 等字符破坏 URL。
     *
     * @param baseUrl 基础地址
     * @param params  查询参数
     * @return 完整 URI
     * @throws IOException 基础地址非法
     */
    static URI withQuery(String baseUrl, Map<String, String> params) throws IOException {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IOException("图片接口地址未配置");
        }
        String trimmed = baseUrl.trim();
        StringBuilder builder = new StringBuilder(trimmed);
        if (!params.isEmpty()) {
            builder.append(trimmed.contains("?") ? '&' : '?');
            boolean first = true;
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(params).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (!first) {
                    builder.append('&');
                }
                builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        try {
            return URI.create(builder.toString());
        } catch (IllegalArgumentException e) {
            throw new IOException("图片接口地址不合法", e);
        }
    }
}
