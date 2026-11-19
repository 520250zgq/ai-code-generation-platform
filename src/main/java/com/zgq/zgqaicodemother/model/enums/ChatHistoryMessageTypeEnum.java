package com.zgq.zgqaicodemother.model.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 对话历史消息类型。
 * 失败的 AI 回复仍以 AI 消息保存，消息内容包含失败信息，保证同一轮对话完整可见。
 */
@Getter
public enum ChatHistoryMessageTypeEnum {

    USER("user", "用户"),
    AI("ai", "AI");

    private final String value;
    private final String text;

    /**
     * 绑定数据库值和展示文案。
     */
    ChatHistoryMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据数据库保存值获取枚举。
     *
     * @param value 消息类型值
     * @return 对应枚举；未匹配时返回 null
     */
    public static ChatHistoryMessageTypeEnum getEnumByValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElse(null);
    }
}
