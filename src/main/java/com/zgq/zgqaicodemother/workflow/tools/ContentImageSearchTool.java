package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.http.HttpJsonClient;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import com.zgq.zgqaicodemother.workflow.tools.provider.ContentImageProvider;
import com.zgq.zgqaicodemother.workflow.tools.provider.PexelsImageProvider;
import com.zgq.zgqaicodemother.workflow.tools.provider.PicsumImageProvider;
import com.zgq.zgqaicodemother.workflow.tools.provider.PixabayImageProvider;
import com.zgq.zgqaicodemother.workflow.tools.provider.UnsplashImageProvider;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内容图片收集工具。
 *
 * <p>渠道选择策略：优先使用配置的 provider；auto 模式按 Pexels、Pixabay、Unsplash 的顺序
 * 选择第一个已配置密钥且返回结果的渠道，全部不可用时退回 Picsum 免密钥占位图。这样即使
 * Pexels 暂时无法申请 Key，工作流仍然可以拿到真实可访问的图片素材。</p>
 *
 * <p>该方法也带有 {@link Tool} 注解，后续如果接入图片收集 AI Agent，可以直接注册为工具；
 * 当前工作流为了可控性和并发效率，由图片收集服务按 AI 计划直接调用。</p>
 */
@Slf4j
@Component
public class ContentImageSearchTool {

    private final WorkflowProperties.ContentImage config;

    private final List<ContentImageProvider> configuredProviders;

    private final ContentImageProvider fallbackProvider;

    public ContentImageSearchTool(HttpJsonClient httpClient, WorkflowProperties properties) {
        this.config = properties.getContentImage();
        this.configuredProviders = List.of(
                new PexelsImageProvider(httpClient, config),
                new PixabayImageProvider(httpClient, config),
                new UnsplashImageProvider(httpClient, config));
        this.fallbackProvider = new PicsumImageProvider(config.getFallback());
    }

    /**
     * 搜索内容相关图片。
     *
     * @param query 搜索关键词
     * @return 图片列表，失败或未配置渠道时返回空列表
     */
    @Tool("搜索内容相关的图片，用于网站内容展示；query 是搜索关键词，建议使用中文或英文名词短语")
    public List<ImageResource> searchContentImages(@P("搜索关键词，例如 科技办公、城市夜景") String query) {
        String safeQuery = WorkflowImageSupport.sanitizeQuery(query);
        if (safeQuery == null) {
            return List.of();
        }
        int limit = normalizeLimit(config.getPerPage());
        String provider = StrUtil.blankToDefault(config.getProvider(), "auto")
                .trim()
                .toLowerCase(Locale.ROOT);

        List<ContentImageProvider> candidates = selectCandidates(provider);
        for (ContentImageProvider candidate : candidates) {
            if (!candidate.available()) {
                continue;
            }
            try {
                List<ImageResource> images = candidate.search(safeQuery, limit);
                List<ImageResource> cleaned = WorkflowImageSupport.dedupeAndCap(images, limit);
                if (!cleaned.isEmpty()) {
                    log.info("内容图片搜索完成，provider={}，limit={}，count={}",
                            candidate.name(), limit, cleaned.size());
                    return cleaned;
                }
                log.info("内容图片渠道没有返回结果，尝试下一个渠道，provider={}", candidate.name());
            } catch (Exception e) {
                // 单个渠道故障不能阻断工作流，记录后继续降级。
                log.warn("内容图片渠道调用失败，provider={}，原因={}", candidate.name(), e.getMessage());
                log.debug("内容图片渠道调用失败详情", e);
            }
        }
        if (fallbackProvider.available()) {
            try {
                List<ImageResource> images = fallbackProvider.search(safeQuery, limit);
                log.info("内容图片使用免密钥占位渠道，count={}", images.size());
                return WorkflowImageSupport.dedupeAndCap(images, limit);
            } catch (Exception e) {
                log.warn("占位图片渠道调用失败：{}", e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * 返回各渠道是否可用，供工作流状态接口展示。不包含任何密钥内容。
     *
     * @return 渠道名称到可用状态的映射
     */
    public Map<String, Boolean> describeAvailability() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (ContentImageProvider provider : configuredProviders) {
            result.put(provider.name(), provider.available());
        }
        result.put(fallbackProvider.name(), fallbackProvider.available());
        return result;
    }

    /**
     * 当前配置的 provider 名称，便于状态接口展示。
     *
     * @return provider 名称
     */
    public String configuredProvider() {
        return StrUtil.blankToDefault(config.getProvider(), "auto");
    }

    private List<ContentImageProvider> selectCandidates(String provider) {
        if ("auto".equals(provider) || provider.isBlank()) {
            return configuredProviders;
        }
        List<ContentImageProvider> candidates = new ArrayList<>();
        for (ContentImageProvider candidate : configuredProviders) {
            if (candidate.name().equals(provider)) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty() && ("mock".equals(provider) || "picsum".equals(provider))) {
            candidates.add(fallbackProvider);
        }
        if (candidates.isEmpty()) {
            log.warn("未知的内容图片 provider={}，回退到 auto 模式", provider);
            return configuredProviders;
        }
        return candidates;
    }

    private static int normalizeLimit(int perPage) {
        return Math.max(1, Math.min(perPage, 8));
    }
}
