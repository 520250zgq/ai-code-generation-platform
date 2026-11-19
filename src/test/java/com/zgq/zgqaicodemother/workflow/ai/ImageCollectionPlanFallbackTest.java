package com.zgq.zgqaicodemother.workflow.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片收集规则兜底测试。
 */
class ImageCollectionPlanFallbackTest {

    @Test
    void mapsIndustryKeywordToEnglishQuery() {
        var plan = ImageCollectionPlanFallback.from("做一个科技公司官网");

        assertEquals(1, plan.getContentImageTasks().size());
        assertEquals("technology office", plan.getContentImageTasks().get(0).getQuery());
        assertTrue(plan.getIllustrationTasks().size() >= 1);
    }

    @Test
    void addsDiagramAndLogoForTechnicalBrandPrompt() {
        var plan = ImageCollectionPlanFallback.from("公司官网需要展示系统架构图，并设计品牌 Logo");

        assertEquals(1, plan.getDiagramTasks().size());
        assertTrue(plan.getDiagramTasks().get(0).getMermaidCode().startsWith("flowchart"));
        assertEquals(1, plan.getLogoTasks().size());
    }

    @Test
    void genericPromptOnlyCollectsContentAndIllustration() {
        var plan = ImageCollectionPlanFallback.from("做一个简单的个人介绍页面");

        assertTrue(plan.getDiagramTasks().isEmpty());
        assertTrue(plan.getLogoTasks().isEmpty());
        assertEquals(1, plan.getContentImageTasks().size());
        assertEquals(1, plan.getIllustrationTasks().size());
    }

    @Test
    void emptyPromptStillProducesUsablePlan() {
        var plan = ImageCollectionPlanFallback.from(null);

        assertEquals(1, plan.getContentImageTasks().size());
        assertEquals(1, plan.getIllustrationTasks().size());
    }
}
