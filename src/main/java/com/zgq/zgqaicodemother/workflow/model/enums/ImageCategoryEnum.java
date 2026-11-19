package com.zgq.zgqaicodemother.workflow.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 工作流可收集的图片类别。
 *
 * <p>枚举同时承担两个职责：一是告诉模型“这是什么用途的图片”，二是让聚合逻辑按固定顺序
 * 输出素材，保证同一份需求多次执行时提示词结构稳定。</p>
 */
@Getter
public enum ImageCategoryEnum {

    CONTENT("内容图片", "CONTENT"),
    ILLUSTRATION("插画图片", "ILLUSTRATION"),
    ARCHITECTURE("架构图片", "ARCHITECTURE"),
    LOGO("Logo 图片", "LOGO");

    private final String text;

    private final String value;

    ImageCategoryEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据持久化值查找枚举，未知值返回 null，调用方据此忽略脏数据。
     *
     * @param value 枚举 value
     * @return 匹配的枚举，找不到时返回 null
     */
    public static ImageCategoryEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ImageCategoryEnum category : values()) {
            if (category.value.equals(value)) {
                return category;
            }
        }
        return null;
    }
}
