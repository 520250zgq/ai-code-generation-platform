package com.zgq.zgqaicodemother.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 生成站点预览状态配置绑定。
 */
@Configuration
public class PreviewConfig {

    /**
     * 绑定 app.preview 下的预览配置。
     *
     * @return 预览配置
     */
    @Bean
    @ConfigurationProperties(prefix = "app.preview")
    public PreviewProperties previewProperties() {
        return new PreviewProperties();
    }

    /**
     * 预览状态服务：版本号与构建状态优先落 Redis，失败时退化为单实例内存记录。
     *
     * @param redisTemplate 与应用共用的 Redis 模板
     * @param properties    预览配置
     * @return 预览状态服务
     */
    @Bean
    public PreviewService previewService(StringRedisTemplate redisTemplate, PreviewProperties properties) {
        return new PreviewService(redisTemplate, properties);
    }
}
