package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.tools.mermaid.MermaidRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 架构图工具的校验、上传和失败降级测试。
 */
class MermaidDiagramToolTest {

    @TempDir
    private Path tempDir;

    @Test
    void rendersUploadsAndCleansTempDirectory() throws Exception {
        MermaidRenderer renderer = mock(MermaidRenderer.class);
        CosManager cosManager = mock(CosManager.class);
        when(renderer.available()).thenReturn(true);
        when(cosManager.isAvailable()).thenReturn(true);
        Path image = Files.write(tempDir.resolve("diagram.png"), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        when(renderer.render(anyString())).thenReturn(
                new MermaidRenderer.RenderedDiagram(image, "png", tempDir));
        when(cosManager.uploadWorkflowImage(any(), anyString()))
                .thenReturn("https://cdn.example.com/workflow/diagram.png");
        MermaidDiagramTool tool = new MermaidDiagramTool(renderer, cosManager, new WorkflowProperties());

        List<ImageResource> images = tool.generateMermaidDiagram(
                "flowchart LR\n  A[用户] --> B[服务]", "展示前后端结构");

        assertEquals(1, images.size());
        assertEquals("https://cdn.example.com/workflow/diagram.png", images.get(0).getUrl());
        assertEquals("mermaid", images.get(0).getSource());
        verify(renderer).render(anyString());
        verify(cosManager).uploadWorkflowImage(any(), anyString());
        assertFalse(Files.exists(tempDir), "渲染临时目录必须被清理");
    }

    @Test
    void invalidCodeSkipsRenderer() throws Exception {
        MermaidRenderer renderer = mock(MermaidRenderer.class);
        CosManager cosManager = mock(CosManager.class);
        MermaidDiagramTool tool = new MermaidDiagramTool(renderer, cosManager, new WorkflowProperties());

        List<ImageResource> images = tool.generateMermaidDiagram("hello world", "描述");

        assertTrue(images.isEmpty());
        verify(renderer, never()).render(anyString());
    }

    @Test
    void uploadFailureReturnsEmptyInsteadOfThrowing() throws Exception {
        MermaidRenderer renderer = mock(MermaidRenderer.class);
        CosManager cosManager = mock(CosManager.class);
        when(renderer.available()).thenReturn(true);
        when(cosManager.isAvailable()).thenReturn(true);
        Path image = Files.write(tempDir.resolve("diagram.png"), new byte[]{1});
        when(renderer.render(anyString())).thenReturn(
                new MermaidRenderer.RenderedDiagram(image, "png", tempDir));
        when(cosManager.uploadWorkflowImage(any(), anyString()))
                .thenThrow(new RuntimeException("cos down"));
        MermaidDiagramTool tool = new MermaidDiagramTool(renderer, cosManager, new WorkflowProperties());

        assertTrue(tool.generateMermaidDiagram("flowchart LR\n A-->B", "描述").isEmpty());
        assertFalse(Files.exists(tempDir));
    }

    @Test
    void unavailableRendererReturnsEmpty() {
        MermaidRenderer renderer = mock(MermaidRenderer.class);
        CosManager cosManager = mock(CosManager.class);
        when(renderer.available()).thenReturn(false);
        MermaidDiagramTool tool = new MermaidDiagramTool(renderer, cosManager, new WorkflowProperties());

        assertTrue(tool.generateMermaidDiagram("flowchart LR\n A-->B", "描述").isEmpty());
        assertFalse(tool.available());
    }
}
