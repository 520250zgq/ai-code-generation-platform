package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import com.zgq.zgqaicodemother.workflow.tools.mermaid.MermaidCodeValidator;
import com.zgq.zgqaicodemother.workflow.tools.mermaid.MermaidRenderer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 架构图生成工具。
 *
 * <p>架构图必须根据具体描述定制，不能通过关键词搜索。因此流程是：模型生成 Mermaid 文本 ->
 * 安全校验 -> mermaid-cli 渲染为 PNG -> 上传 COS -> 返回公网 URL。任何一步失败都只记录日志并
 * 返回空列表，由工作流继续生成网站，而不是让整次生成失败。</p>
 */
@Slf4j
@Component
public class MermaidDiagramTool {

    private final MermaidRenderer renderer;

    private final CosManager cosManager;

    private final WorkflowProperties.Mermaid config;

    public MermaidDiagramTool(MermaidRenderer renderer, CosManager cosManager, WorkflowProperties properties) {
        this.renderer = renderer;
        this.cosManager = cosManager;
        this.config = properties.getMermaid();
    }

    /**
     * 将 Mermaid 代码转换为架构图并上传对象存储。
     *
     * @param mermaidCode Mermaid 图表代码
     * @param description 图表用途描述
     * @return 架构图素材列表；失败时返回空列表
     */
    @Tool("将 Mermaid 代码转换为架构图图片并上传，用于展示系统结构和技术关系")
    public List<ImageResource> generateMermaidDiagram(
            @P("Mermaid 图表代码，必须是合法语法，例如 graph TD; A-->B") String mermaidCode,
            @P("架构图用途描述，例如 展示前后端分离架构") String description) {
        if (!config.isEnabled()) {
            return List.of();
        }
        String safeCode;
        try {
            safeCode = MermaidCodeValidator.validate(mermaidCode, config.getMaxCodeLength(),
                    config.isAllowExternalResources());
        } catch (IllegalArgumentException e) {
            log.warn("Mermaid 代码校验失败：{}", e.getMessage());
            return List.of();
        }
        if (!renderer.available()) {
            log.warn("Mermaid 渲染器不可用，跳过架构图生成");
            return List.of();
        }
        if (!cosManager.isAvailable()) {
            log.warn("对象存储不可用，无法上传架构图，跳过架构图生成");
            return List.of();
        }
        String safeDescription = WorkflowImageSupport.sanitizeText(description,
                WorkflowImageSupport.MAX_DESCRIPTION_LENGTH);
        if (StrUtil.isBlank(safeDescription)) {
            safeDescription = "系统架构图";
        }
        MermaidRenderer.RenderedDiagram diagram = null;
        try {
            diagram = renderer.render(safeCode);
            File imageFile = diagram.file().toFile();
            String url = cosManager.uploadWorkflowImage(imageFile, config.getCosPrefix());
            ImageResource image = WorkflowImageSupport.create(
                    ImageCategoryEnum.ARCHITECTURE, safeDescription, url, "mermaid");
            if (image == null) {
                log.warn("架构图上传成功但返回地址不合法");
                return List.of();
            }
            List<ImageResource> result = new ArrayList<>(1);
            result.add(image);
            return result;
        } catch (Exception e) {
            log.warn("生成架构图失败：{}", e.getMessage());
            log.debug("生成架构图失败详情", e);
            return List.of();
        } finally {
            if (diagram != null) {
                FileUtil.del(diagram.tempDir().toFile());
            }
        }
    }

    /**
     * 渲染器和对象存储是否都可用，供状态接口展示。
     *
     * @return 可用返回 true
     */
    public boolean available() {
        return config.isEnabled() && renderer.available() && cosManager.isAvailable();
    }
}
