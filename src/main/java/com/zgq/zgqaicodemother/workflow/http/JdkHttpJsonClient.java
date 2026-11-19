package com.zgq.zgqaicodemother.workflow.http;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于 JDK HttpClient 的工作流 HTTP 实现。
 *
 * <p>JDK 客户端不额外引入依赖，同时可以精确控制超时、响应大小和重定向。所有方法都只在
 * 内存中处理有限大小的响应，避免外部接口返回超大内容导致内存占用失控。</p>
 */
@Slf4j
@Component
public class JdkHttpJsonClient implements HttpJsonClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;

    private static final int MAX_ALLOWED_BYTES = 32 * 1024 * 1024;

    private static final int ERROR_BODY_LIMIT = 2 * 1024;

    private static final int MAX_REDIRECTS = 3;

    private static final String USER_AGENT = "zgq-ai-code-mother-workflow/1.0";

    private final HttpClient httpClient;

    public JdkHttpJsonClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // 手动处理重定向，下载图片时才能逐跳校验域名白名单。
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    /**
     * 供测试替换底层客户端。
     *
     * @param httpClient JDK HTTP 客户端
     */
    JdkHttpJsonClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public JSONObject getJson(URI uri, Map<String, String> headers, Duration timeout, int maxBytes) throws IOException {
        String body = getText(uri, headers, timeout, maxBytes);
        return parseJson(body, uri);
    }

    @Override
    public JSONObject postJson(URI uri, Map<String, String> headers, String jsonBody,
                               Duration timeout, int maxBytes) throws IOException {
        WorkflowHttpSupport.requireSafeHttpsUri(uri);
        String body = execute(uri, "POST", headers, jsonBody, timeout, maxBytes);
        return parseJson(body, uri);
    }

    @Override
    public String getText(URI uri, Map<String, String> headers, Duration timeout, int maxBytes) throws IOException {
        WorkflowHttpSupport.requireSafeHttpsUri(uri);
        return execute(uri, "GET", headers, null, timeout, maxBytes);
    }

    @Override
    public DownloadedResource download(URI uri, Set<String> allowedHostSuffixes,
                                       Duration timeout, int maxBytes) throws IOException {
        URI current = WorkflowHttpSupport.requireSafeHttpsUri(uri);
        Duration actualTimeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        int actualMaxBytes = normalizeMaxBytes(maxBytes);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            WorkflowHttpSupport.requireAllowedHost(current, allowedHostSuffixes);
            HttpRequest request = newRequest(current, "GET", Map.of("Accept", "image/*"), null, actualTimeout);
            HttpResponse<InputStream> response = send(request);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                closeQuietly(response.body());
                if (StrUtil.isBlank(location)) {
                    throw new WorkflowHttpException(status, "图片下载重定向缺少 Location");
                }
                current = current.resolve(location.trim());
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new WorkflowHttpException(status, "图片下载失败，HTTP " + status);
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .map(value -> value.split(";")[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("");
            if (!contentType.startsWith("image/")) {
                closeQuietly(response.body());
                throw new WorkflowHttpException(status, "图片地址返回的内容类型不是图片：" + contentType);
            }
            try (InputStream input = response.body()) {
                byte[] content = readLimited(input, actualMaxBytes);
                if (content.length == 0) {
                    throw new WorkflowHttpException(status, "图片内容为空");
                }
                log.debug("图片下载成功，host={}，size={} bytes", current.getHost(), content.length);
                return new DownloadedResource(content, contentType, current);
            }
        }
        throw new WorkflowHttpException("图片下载重定向次数超过上限");
    }

    /**
     * 执行一次请求并读取有限大小的响应文本。
     */
    private String execute(URI uri, String method, Map<String, String> headers, String body,
                           Duration timeout, int maxBytes) throws IOException {
        Duration actualTimeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        int actualMaxBytes = normalizeMaxBytes(maxBytes);
        HttpRequest request = newRequest(uri, method, headers, body, actualTimeout);
        HttpResponse<InputStream> response = send(request);
        int status = response.statusCode();
        try (InputStream input = response.body()) {
            if (status < 200 || status >= 300) {
                byte[] errorBody = readLimited(input, ERROR_BODY_LIMIT);
                String detail = new String(errorBody, StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ")
                        .trim();
                String message = "HTTP " + status + (detail.isEmpty() ? "" : "：" + detail);
                log.warn("工作流外部请求失败，host={}，status={}", uri.getHost(), status);
                throw new WorkflowHttpException(status, message);
            }
            byte[] content = readLimited(input, actualMaxBytes);
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private HttpRequest newRequest(URI uri, String method, Map<String, String> headers,
                                   String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT);
        boolean hasAcceptHeader = headers != null && headers.keySet().stream()
                .anyMatch("Accept"::equalsIgnoreCase);
        if (!hasAcceptHeader) {
            builder.header("Accept", "application/json");
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                String value = WorkflowHttpSupport.requireSafeHeaderValue(entry.getValue());
                if (StrUtil.isBlank(name) || value == null) {
                    continue;
                }
                builder.header(name, value);
            }
        }
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowHttpException("HTTP 请求被中断", e);
        } catch (IOException e) {
            throw new WorkflowHttpException("HTTP 请求失败：" + request.uri().getHost(), e);
        }
    }

    private static JSONObject parseJson(String body, URI uri) throws IOException {
        if (StrUtil.isBlank(body)) {
            throw new WorkflowHttpException("接口返回空响应：" + uri.getHost());
        }
        try {
            return JSONUtil.parseObj(body);
        } catch (JSONException e) {
            throw new WorkflowHttpException("接口返回内容不是合法 JSON：" + uri.getHost(), e);
        }
    }

    private static int normalizeMaxBytes(int maxBytes) {
        if (maxBytes <= 0) {
            return DEFAULT_MAX_BYTES;
        }
        return Math.min(maxBytes, MAX_ALLOWED_BYTES);
    }

    /**
     * 读取指定上限的内容，超过上限立即失败，避免把超大响应全部载入内存。
     */
    static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new WorkflowHttpException("响应内容超过大小限制：" + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // 关闭响应流失败不影响主流程。
        }
    }
}
