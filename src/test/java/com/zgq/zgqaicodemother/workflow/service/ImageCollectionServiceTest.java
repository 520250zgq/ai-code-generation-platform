package com.zgq.zgqaicodemother.workflow.service;

import com.zgq.zgqaicodemother.workflow.ai.ImageCollectionPlanService;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.tools.ContentImageSearchTool;
import com.zgq.zgqaicodemother.workflow.tools.LogoGeneratorTool;
import com.zgq.zgqaicodemother.workflow.tools.MermaidDiagramTool;
import com.zgq.zgqaicodemother.workflow.tools.UndrawIllustrationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片收集服务的规划、并发聚合、限流和容错测试。
 */
class ImageCollectionServiceTest {

    private ImageCollectionPlanService planService;

    private ContentImageSearchTool contentTool;

    private UndrawIllustrationTool undrawTool;

    private MermaidDiagramTool mermaidTool;

    private LogoGeneratorTool logoTool;

    private WorkflowProperties properties;

    private ImageCollectionService service;

    @BeforeEach
    void setUp() {
        planService = mock(ImageCollectionPlanService.class);
        contentTool = mock(ContentImageSearchTool.class);
        undrawTool = mock(UndrawIllustrationTool.class);
        mermaidTool = mock(MermaidDiagramTool.class);
        logoTool = mock(LogoGeneratorTool.class);
        properties = new WorkflowProperties();
        service = new ImageCollectionService(planService, contentTool, undrawTool,
                mermaidTool, logoTool, properties);
    }

    @Test
    void collectsAllCategoriesInStableOrder() {
        when(planService.planImageCollection(anyString())).thenReturn(plan(
                List.of("office"), List.of("team"), List.of("flowchart LR\n A-->B"), List.of("brand")));
        when(contentTool.describeAvailability()).thenReturn(Map.of("pexels", true));
        when(contentTool.searchContentImages("office")).thenReturn(List.of(image(ImageCategoryEnum.CONTENT, "content")));
        when(undrawTool.available()).thenReturn(true);
        when(undrawTool.searchIllustrations("team")).thenReturn(List.of(image(ImageCategoryEnum.ILLUSTRATION, "illustration")));
        when(mermaidTool.available()).thenReturn(true);
        when(mermaidTool.generateMermaidDiagram(anyString(), anyString()))
                .thenReturn(List.of(image(ImageCategoryEnum.ARCHITECTURE, "architecture")));
        when(logoTool.available()).thenReturn(true);
        when(logoTool.generateLogos("brand")).thenReturn(List.of(image(ImageCategoryEnum.LOGO, "logo")));

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("做一个企业官网");

        assertEquals(4, outcome.images().size());
        assertEquals(ImageCategoryEnum.CONTENT, outcome.images().get(0).getCategory());
        assertEquals(ImageCategoryEnum.ILLUSTRATION, outcome.images().get(1).getCategory());
        assertEquals(ImageCategoryEnum.ARCHITECTURE, outcome.images().get(2).getCategory());
        assertEquals(ImageCategoryEnum.LOGO, outcome.images().get(3).getCategory());
        assertTrue(outcome.warnings().isEmpty(), outcome.warnings().toString());
    }

    @Test
    void planFailureUsesFallbackAndWarns() {
        when(planService.planImageCollection(anyString())).thenThrow(new RuntimeException("model timeout"));
        when(contentTool.describeAvailability()).thenReturn(Map.of("picsum", true));
        when(contentTool.searchContentImages(anyString())).thenReturn(List.of(image(ImageCategoryEnum.CONTENT, "content")));
        when(undrawTool.available()).thenReturn(true);
        when(undrawTool.searchIllustrations(anyString()))
                .thenReturn(List.of(image(ImageCategoryEnum.ILLUSTRATION, "illustration")));

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("做一个科技公司官网");

        assertEquals(2, outcome.images().size());
        assertTrue(outcome.warnings().stream().anyMatch(w -> w.contains("兜底")), outcome.warnings().toString());
        assertTrue(outcome.plan().getContentImageTasks().size() >= 1);
    }

    @Test
    void truncatesOverLimitPlanWithoutNoisyWarning() {
        when(planService.planImageCollection(anyString())).thenReturn(plan(
                List.of("one", "two", "three"), List.of(), List.of(), List.of()));
        properties.getContentImage().setMaxTasks(1);
        when(contentTool.describeAvailability()).thenReturn(Map.of("pexels", true));
        when(contentTool.searchContentImages(anyString())).thenReturn(List.of(image(ImageCategoryEnum.CONTENT, "content")));

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("需求");

        verify(contentTool, times(1)).searchContentImages(anyString());
        assertEquals(1, outcome.plan().getContentImageTasks().size());
        assertTrue(outcome.warnings().stream().noneMatch(w -> w.contains("截断")),
                outcome.warnings().toString());
    }

    @Test
    void oneTaskFailureDoesNotDropOtherImages() {
        when(planService.planImageCollection(anyString())).thenReturn(plan(
                List.of("office"), List.of("team"), List.of(), List.of()));
        when(contentTool.describeAvailability()).thenReturn(Map.of("pexels", true));
        when(contentTool.searchContentImages("office")).thenThrow(new RuntimeException("network"));
        when(undrawTool.available()).thenReturn(true);
        when(undrawTool.searchIllustrations("team"))
                .thenReturn(List.of(image(ImageCategoryEnum.ILLUSTRATION, "illustration")));

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("需求");

        assertEquals(1, outcome.images().size());
        assertTrue(outcome.warnings().stream().anyMatch(w -> w.contains("内容图片任务失败")),
                outcome.warnings().toString());
    }

    @Test
    void capsTotalImages() {
        when(planService.planImageCollection(anyString())).thenReturn(plan(
                List.of("office"), List.of(), List.of(), List.of()));
        properties.setMaxTotalImages(2);
        when(contentTool.describeAvailability()).thenReturn(Map.of("pexels", true));
        List<ImageResource> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(image(ImageCategoryEnum.CONTENT, "content-" + i));
        }
        when(contentTool.searchContentImages("office")).thenReturn(many);

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("需求");

        assertEquals(2, outcome.images().size());
    }

    @Test
    void reportsUnavailableChannels() {
        when(planService.planImageCollection(anyString())).thenReturn(plan(
                List.of("office"), List.of("team"), List.of("flowchart LR\n A-->B"), List.of("brand")));
        when(contentTool.describeAvailability()).thenReturn(Map.of("pexels", false, "picsum", false));
        when(contentTool.searchContentImages(anyString())).thenReturn(List.of());
        when(undrawTool.available()).thenReturn(false);
        when(undrawTool.searchIllustrations(anyString())).thenReturn(List.of());
        when(mermaidTool.available()).thenReturn(false);
        when(mermaidTool.generateMermaidDiagram(anyString(), anyString())).thenReturn(List.of());
        when(logoTool.available()).thenReturn(false);
        when(logoTool.generateLogos(anyString())).thenReturn(List.of());

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("需求");

        assertTrue(outcome.images().isEmpty());
        String warnings = String.join("\n", outcome.warnings());
        assertTrue(warnings.contains("内容图片渠道"), warnings);
        assertTrue(warnings.contains("unDraw"), warnings);
        assertTrue(warnings.contains("架构图渠道"), warnings);
        assertTrue(warnings.contains("Logo 渠道"), warnings);
    }

    @Test
    void allChannelsDisabledRespectsConfiguration() {
        when(planService.planImageCollection(anyString())).thenReturn(plan(
                List.of("office"), List.of("team"), List.of(), List.of()));
        properties.getContentImage().setMaxTasks(0);
        properties.getUndraw().setMaxTasks(0);
        properties.getMermaid().setMaxTasks(0);
        properties.getLogo().setMaxTasks(0);

        ImageCollectionService.ImageCollectionOutcome outcome = service.collect("需求");

        assertTrue(outcome.images().isEmpty());
        verify(contentTool, org.mockito.Mockito.never()).searchContentImages(anyString());
        assertTrue(outcome.warnings().stream().anyMatch(w -> w.contains("没有收集到")), outcome.warnings().toString());
    }

    private static ImageCollectionPlan plan(List<String> contentQueries, List<String> illustrationQueries,
                                            List<String> diagrams, List<String> logos) {
        return ImageCollectionPlan.builder()
                .contentImageTasks(contentQueries.stream()
                        .map(ImageCollectionPlan.ContentImageTask::new).toList())
                .illustrationTasks(illustrationQueries.stream()
                        .map(ImageCollectionPlan.IllustrationTask::new).toList())
                .diagramTasks(diagrams.stream()
                        .map(code -> new ImageCollectionPlan.DiagramTask(code, "架构图")).toList())
                .logoTasks(logos.stream()
                        .map(ImageCollectionPlan.LogoTask::new).toList())
                .build();
    }

    private static ImageResource image(ImageCategoryEnum category, String path) {
        return ImageResource.builder()
                .category(category)
                .description(path)
                .url("https://cdn.example.com/" + path + ".png")
                .source("test")
                .build();
    }
}
