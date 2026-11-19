package com.zgq.zgqaicodemother.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 统一 JSON 序列化规则，避免前端处理雪花 id 时发生精度丢失。
 */
@Configuration
public class JsonConfig {

    /**
     * 把 Long 和 long 序列化为字符串，前端可直接以原始 id 发起后续请求。
     */
    @Bean
    public JsonMapperBuilderCustomizer longToStringCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return builder -> builder.addModule(module);
    }
}