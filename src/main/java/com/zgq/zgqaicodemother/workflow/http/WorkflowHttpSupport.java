package com.zgq.zgqaicodemother.workflow.http;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * URL 和请求头安全校验工具。
 *
 * <p>工作流会访问图片站点、Mermaid 渲染器和 DashScope，所有地址都来自配置或外部响应。
 * 这里统一限制 HTTPS、禁止 URL 中携带用户信息和 CR/LF，并为下载类请求提供域名白名单，
 * 降低 SSRF 和请求头注入风险。</p>
 */
public final class WorkflowHttpSupport {

    private WorkflowHttpSupport() {
    }

    /**
     * 校验请求地址：必须是非空 HTTPS 地址，且不能包含用户信息、控制字符或片段。
     *
     * @param uri 待校验地址
     * @return 原地址，便于链式调用
     */
    public static URI requireSafeHttpsUri(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("请求地址不能为空");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("只允许 HTTPS 地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("请求地址缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("请求地址不能包含用户信息");
        }
        String raw = uri.toString();
        if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0 || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("请求地址包含非法字符");
        }
        return uri;
    }

    /**
     * 校验下载域名是否在白名单内。使用后缀匹配，必须命中完整域名边界，
     * 例如 aliyuncs.com 允许 a.aliyuncs.com，但不允许 evil-aliyuncs.com。
     *
     * @param uri                 待校验地址
     * @param allowedHostSuffixes 允许的域名后缀集合
     */
    public static void requireAllowedHost(URI uri, Set<String> allowedHostSuffixes) {
        requireSafeHttpsUri(uri);
        if (allowedHostSuffixes == null || allowedHostSuffixes.isEmpty()) {
            throw new IllegalArgumentException("下载域名白名单为空");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String suffix : allowedHostSuffixes) {
            if (suffix == null) {
                continue;
            }
            String normalized = suffix.trim().toLowerCase(Locale.ROOT);
            while (normalized.startsWith(".")) {
                normalized = normalized.substring(1);
            }
            if (normalized.isEmpty()) {
                continue;
            }
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return;
            }
        }
        throw new IllegalArgumentException("图片地址不在允许的域名范围内：" + host);
    }

    /**
     * 校验单个请求头的值，拒绝 CR/LF，避免请求头注入。
     *
     * @param value 请求头值
     * @return 原值
     */
    public static String requireSafeHeaderValue(String value) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("请求头包含非法字符");
        }
        return value;
    }
}
