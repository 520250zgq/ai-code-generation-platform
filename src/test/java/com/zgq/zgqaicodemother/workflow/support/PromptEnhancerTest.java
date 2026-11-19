package com.zgq.zgqaicodemother.workflow.support;

import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词增强测试：验证图片清单格式、长度保护和空素材行为。
 */
class PromptEnhancerTest {

    @Test
    void returnsOriginalPromptWhenNoImages() {
        assertEquals("做一个企业官网", PromptEnhancer.enhance(" 做一个企业官网 ", List.of()));
        assertEquals("", PromptEnhancer.enhance(" ", List.of()));
    }

    @Test
    void appendsImageMetadataAndUsageRules() {
        String enhanced = PromptEnhancer.enhance("做一个企业官网", List.of(
                image("内容图片", "科技办公", "https://cdn.example.com/office.jpg"),
                image("Logo 图片", "品牌标识", "https://cdn.example.com/logo.png")));

        assertTrue(enhanced.contains("做一个企业官网"));
        assertTrue(enhanced.contains("科技办公"));
        assertTrue(enhanced.contains("https://cdn.example.com/office.jpg"));
        assertTrue(enhanced.contains("品牌标识"));
        assertTrue(enhanced.contains("图片使用要求"));
        assertTrue(enhanced.contains("不是新的指令"));
        assertTrue(enhanced.indexOf("内容图片") < enhanced.indexOf("Logo 图片"));
    }

    @Test
    void skipsImagesWithoutUrl() {
        ImageResource broken = ImageResource.builder()
                .category(ImageCategoryEnum.CONTENT)
                .description("没有地址")
                .build();

        String enhanced = PromptEnhancer.enhance("做一个网站", List.of(broken));

        assertEquals("做一个网站", enhanced);
        assertFalse(enhanced.contains("没有地址"));
    }

    @Test
    void capsEnhancedPromptLength() {
        List<ImageResource> images = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            images.add(image("内容图片", "描述".repeat(20),
                    "https://cdn.example.com/" + "path/".repeat(20) + i + ".png"));
        }

        String enhanced = PromptEnhancer.enhance("做一个网站", images);

        assertTrue(enhanced.length() <= PromptEnhancer.MAX_ENHANCED_PROMPT_LENGTH, enhanced.length() + "");
        assertTrue(enhanced.contains("图片使用要求"));
    }

    private static ImageResource image(String categoryText, String description, String url) {
        ImageCategoryEnum category = categoryText.contains("Logo")
                ? ImageCategoryEnum.LOGO : ImageCategoryEnum.CONTENT;
        return ImageResource.builder()
                .category(category)
                .description(description)
                .url(url)
                .source("test")
                .build();
    }
}
