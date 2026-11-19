package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.DownloadedResource;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logo 图片生成工具。
 *
 * <p>Logo 需要根据品牌名称、行业和风格定制，不能通过图片搜索获得，因此调用阿里云百炼
 * DashScope 的万相文生图接口。接口是异步任务模式：先提交任务，再轮询任务状态，最后拿到
 * 临时图片地址。由于 DashScope 返回的 URL 有有效期，默认下载后转存到 COS，保证生成出的
 * 网站长期可访问；转存失败时仍返回原始地址，不让 Logo 成为工作流的阻塞点。</p>
 *
 * <p>所有请求都设置超时，轮询有总时长上限，失败只记录日志并返回空列表。</p>
 */
@Slf4j
@Component
public class LogoGeneratorTool {

    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final String TEXT_TO_IMAGE_PATH = "/api/v1/services/aigc/text2image/image-synthesis";

    private static final String TASK_PATH = "/api/v1/tasks/";

    private final HttpJsonClient httpClient;

    private final CosManager cosManager;

    private final WorkflowProperties.Logo config;

    public LogoGeneratorTool(HttpJsonClient httpClient, CosManager cosManager, WorkflowProperties properties) {
        this.httpClient = httpClient;
        this.cosManager = cosManager;
        this.config = properties.getLogo();
    }

    /**
     * 根据描述生成 Logo 图片。
     *
     * @param description Logo 设计描述，例如品牌名称、行业、颜色和风格
     * @return Logo 素材列表；未配置密钥或生成失败时返回空列表
     */
    @Tool("根据文字描述生成 Logo 设计图片，用于网站品牌标识；描述中可以包含品牌名称、行业和风格")
    public List<ImageResource> generateLogos(
            @P("Logo 设计描述，例如 一家名为云舟的科技公司，蓝紫渐变，简洁几何风格") String description) {
        if (!available()) {
            return List.of();
        }
        String safeDescription = WorkflowImageSupport.sanitizeText(description,
                WorkflowImageSupport.MAX_DESCRIPTION_LENGTH);
        if (StrUtil.isBlank(safeDescription)) {
            return List.of();
        }
        try {
            String taskId = submitTask(safeDescription);
            String imageUrl = pollTask(taskId);
            if (StrUtil.isBlank(imageUrl)) {
                log.warn("Logo 任务完成但没有返回图片地址，taskId={}", taskId);
                return List.of();
            }
            String finalUrl = storeImage(imageUrl);
            if (!WorkflowImageSupport.isSafePublicUrl(finalUrl)) {
                log.warn("Logo 图片地址不合法，已跳过");
                return List.of();
            }
            ImageResource image = WorkflowImageSupport.create(
                    ImageCategoryEnum.LOGO, safeDescription, finalUrl, "dashscope");
            if (image == null) {
                return List.of();
            }
            List<ImageResource> result = new ArrayList<>(1);
            result.add(image);
            return result;
        } catch (Exception e) {
            log.warn("生成 Logo 失败：{}", e.getMessage());
            log.debug("生成 Logo 失败详情", e);
            return List.of();
        }
    }

    /**
     * Logo 渠道是否已配置。
     *
     * @return 启用且配置了 API Key 时返回 true
     */
    public boolean available() {
        return config.isEnabled()
                && StrUtil.isNotBlank(config.getApiKey())
                && StrUtil.isNotBlank(config.getBaseUrl());
    }

    private String submitTask(String description) throws IOException {
        String prompt = "生成 Logo，Logo 中禁止包含任何文字、字母或水印！Logo 设计需求：" + description;
        JSONObject input = JSONUtil.createObj()
                .set("prompt", prompt)
                .set("negative_prompt", StrUtil.blankToDefault(config.getNegativePrompt(), "文字, 字母, 水印"));
        JSONObject parameters = JSONUtil.createObj()
                .set("size", StrUtil.blankToDefault(config.getSize(), "512*512"))
                .set("n", 1);
        String body = JSONUtil.createObj()
                .set("model", StrUtil.blankToDefault(config.getModel(), "wan2.2-t2i-flash"))
                .set("input", input)
                .set("parameters", parameters)
                .toString();
        JSONObject response = httpClient.postJson(buildUri(TEXT_TO_IMAGE_PATH), headers(), body,
                config.getTimeout(), MAX_RESPONSE_BYTES);
        ensureNoApiError(response);
        JSONObject output = response.getJSONObject("output");
        String taskId = output == null ? null : output.getStr("task_id");
        if (StrUtil.isBlank(taskId) || !taskId.matches("[A-Za-z0-9-]+")) {
            throw new IOException("DashScope 未返回合法的任务 id");
        }
        log.info("Logo 生成任务已提交，taskId={}", taskId);
        return taskId;
    }

    private String pollTask(String taskId) throws IOException {
        long deadline = System.nanoTime() + Math.max(10_000L, config.getTimeout().toMillis()) * 1_000_000L;
        int attempts = Math.max(1, config.getMaxPollAttempts());
        Duration interval = config.getPollInterval() == null
                ? Duration.ofSeconds(2) : config.getPollInterval();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (System.nanoTime() > deadline) {
                throw new IOException("Logo 任务轮询超时");
            }
            JSONObject response = httpClient.getJson(buildUri(TASK_PATH + taskId), headers(),
                    config.getTimeout(), MAX_RESPONSE_BYTES);
            ensureNoApiError(response);
            JSONObject output = response.getJSONObject("output");
            if (output == null) {
                throw new IOException("DashScope 任务响应缺少 output");
            }
            String status = StrUtil.blankToDefault(output.getStr("task_status"), "")
                    .toUpperCase(Locale.ROOT);
            if ("SUCCEEDED".equals(status)) {
                return firstImageUrl(output);
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "CANCELLED".equals(status)) {
                String code = output.getStr("code", "");
                String message = output.getStr("message", "");
                throw new IOException("Logo 任务失败：" + code + " " + message);
            }
            sleep(interval);
        }
        throw new IOException("Logo 任务轮询次数超过上限");
    }

    private String firstImageUrl(JSONObject output) {
        JSONArray results = output.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return null;
        }
        for (int i = 0; i < results.size(); i++) {
            Object item = results.get(i);
            if (item instanceof JSONObject json) {
                String url = json.getStr("url");
                if (StrUtil.isNotBlank(url)) {
                    return url;
                }
            } else if (item instanceof String url && StrUtil.isNotBlank(url)) {
                return url;
            }
        }
        return null;
    }

    /**
     * 优先下载并转存 COS；任何失败都退回原始地址，保证 Logo 可用。
     */
    private String storeImage(String imageUrl) {
        if (!config.isStoreToCos() || !cosManager.isAvailable()) {
            return imageUrl;
        }
        Path tempFile = null;
        try {
            DownloadedResource downloaded = httpClient.download(
                    java.net.URI.create(imageUrl), allowedImageHosts(), config.getTimeout(), MAX_IMAGE_BYTES);
            String extension = extensionOf(downloaded.contentType());
            tempFile = Files.createTempFile("zgq-logo-", "." + extension);
            Files.write(tempFile, downloaded.content());
            String url = cosManager.uploadWorkflowImage(tempFile.toFile(), config.getCosPrefix());
            log.info("Logo 图片已转存 COS，size={} bytes", downloaded.content().length);
            return url;
        } catch (Exception e) {
            log.warn("Logo 图片转存 COS 失败，使用原始地址：{}", e.getMessage());
            return imageUrl;
        } finally {
            if (tempFile != null) {
                FileUtil.del(tempFile.toFile());
            }
        }
    }

    private Set<String> allowedImageHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(config.getAllowedImageHosts())) {
            Arrays.stream(config.getAllowedImageHosts().split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .forEach(hosts::add);
        }
        hosts.add("aliyuncs.com");
        hosts.add("alicdn.com");
        return hosts;
    }

    private Map<String, String> headers() {
        return Map.of(
                "Authorization", "Bearer " + config.getApiKey().trim(),
                "Content-Type", "application/json",
                "Accept", "application/json",
                "X-DashScope-Async", "enable");
    }

    private java.net.URI buildUri(String path) {
        String baseUrl = config.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return java.net.URI.create(baseUrl + path);
    }

    private static void ensureNoApiError(JSONObject response) throws IOException {
        if (response == null) {
            return;
        }
        String code = response.getStr("code");
        if (StrUtil.isNotBlank(code)) {
            String message = response.getStr("message", "");
            throw new IOException("DashScope 返回错误：" + code + " " + message);
        }
    }

    private static String extensionOf(String contentType) {
        if (contentType == null) {
            return "png";
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpg";
        }
        if (lower.contains("webp")) {
            return "webp";
        }
        return "png";
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(Math.max(200L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Logo 任务轮询被中断", e);
        }
    }
}
