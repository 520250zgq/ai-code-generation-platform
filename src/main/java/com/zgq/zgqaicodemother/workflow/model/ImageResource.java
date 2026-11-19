package com.zgq.zgqaicodemother.workflow.model;

import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作流收集到的一张图片素材。
 *
 * <p>{@code url} 必须是外部可直接访问的 http(s) 地址，前端或生成出的网站会直接把它放进
 * {@code <img src>}。该对象会进入提示词，因此所有字段在创建时都要经过长度和字符清洗，
 * 不能把外部接口返回的原始文本原样透传。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 图片用途分类。
     */
    private ImageCategoryEnum category;

    /**
     * 面向模型的简短描述，只描述图片内容，不包含任何指令性文字。
     */
    private String description;

    /**
     * 图片的公网访问地址，只允许 http/https。
     */
    private String url;

    /**
     * 素材来源，例如 pexels、pixabay、undraw、mermaid、dashscope。
     * 仅用于日志和问题排查，不参与提示词。
     */
    private String source;

    /**
     * 生成给模型和前端使用的分类名称，枚举为空时返回“未知图片”。
     *
     * @return 中文分类名称
     */
    public String categoryText() {
        return category == null ? "未知图片" : category.getText();
    }
}
