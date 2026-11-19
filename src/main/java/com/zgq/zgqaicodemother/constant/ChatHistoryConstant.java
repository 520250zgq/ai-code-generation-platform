package com.zgq.zgqaicodemother.constant;

/**
 * 对话历史模块常量。
 */
public interface ChatHistoryConstant {

    /**
     * AI 回复失败时写入历史的消息前缀。加载对话记忆时跳过这类占位消息，避免把失败文案发送给模型。
     */
    String AI_FAILURE_MESSAGE_PREFIX = "AI 回复失败：";

    /**
     * 单次从数据库回源对话记忆的最大消息条数，防止配置异常导致一次读取过多历史。
     */
    int MAX_MEMORY_HISTORY_SIZE = 200;
}
