package com.zgq.zgqaicodemother.config;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 路由模型配置回归测试，防止后续误加 JSON 约束导致枚举解析失败。
 */
class AiModelConfigTest {

    @Test
    void routingModelDoesNotAdvertiseJsonSchema() {
        ChatModel routingChatModel = new AiModelConfig().routingChatModel(
                "http://localhost:1", "test-key", "test-model",
                false, false, Duration.ofSeconds(1), 0, 0.0, 32);

        assertFalse(routingChatModel.supportedCapabilities()
                        .contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "路由模型必须使用纯文本枚举格式，不能声明 JSON Schema 能力");
    }
}
