package com.zgq.zgqaicodemother.workflow.support;

import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片素材清洗和 URL 安全校验测试。
 */
class WorkflowImageSupportTest {

    @Test
    void createsSanitizedImage() {
        ImageResource image = WorkflowImageSupport.create(
                ImageCategoryEnum.CONTENT,
                "  科技\u0000办公\n场景  ",
                "https://images.example.com/office.jpg",
                "pexels");

        assertNotNull(image);
        assertEquals("科技办公 场景", image.getDescription());
        assertEquals("https://images.example.com/office.jpg", image.getUrl());
        assertEquals("内容图片", image.categoryText());
    }

    @Test
    void rejectsUnsafeOrBlankUrls() {
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a", null, "test"));
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a", "javascript:alert(1)", "test"));
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a", "file:///etc/passwd", "test"));
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a", "http://localhost/a.png", "test"));
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a", "http://169.254.169.254/a.png", "test"));
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a", "http://192.168.1.1/a.png", "test"));
        assertNull(WorkflowImageSupport.create(ImageCategoryEnum.CONTENT, "a",
                "https://images.example.com/a.png\r\nX-Test: 1", "test"));
    }

    @Test
    void acceptsPublicHttpsAndHttpUrls() {
        assertTrue(WorkflowImageSupport.isSafePublicUrl("https://cdn.example.com/a.png"));
        assertTrue(WorkflowImageSupport.isSafePublicUrl("http://cdn.example.com/a.png"));
        assertFalse(WorkflowImageSupport.isSafePublicUrl("https://user:pass@cdn.example.com/a.png"));
        assertFalse(WorkflowImageSupport.isSafePublicUrl("https://127.0.0.1/a.png"));
    }

    @Test
    void sanitizesQueryAndDescriptionLength() {
        String query = WorkflowImageSupport.sanitizeQuery("  科技\u0000  办公  ");
        assertEquals("科技 办公", query);
        assertNull(WorkflowImageSupport.sanitizeQuery(" \u0000 "));

        String description = WorkflowImageSupport.sanitizeText("x".repeat(500), 10);
        assertEquals(10, description.length());
    }

    @Test
    void dedupesAndCapsByUrl() {
        ImageResource first = image("https://cdn.example.com/a.png");
        ImageResource duplicate = image("https://cdn.example.com/a.png");
        ImageResource second = image("https://cdn.example.com/b.png");

        List<ImageResource> result = WorkflowImageSupport.dedupeAndCap(
                List.of(first, duplicate, second), 10);

        assertEquals(2, result.size());
        assertEquals(List.of(first, second), result);
        assertEquals(1, WorkflowImageSupport.dedupeAndCap(List.of(first, second), 1).size());
    }

    private static ImageResource image(String url) {
        return ImageResource.builder()
                .category(ImageCategoryEnum.CONTENT)
                .description("desc")
                .url(url)
                .source("test")
                .build();
    }
}
