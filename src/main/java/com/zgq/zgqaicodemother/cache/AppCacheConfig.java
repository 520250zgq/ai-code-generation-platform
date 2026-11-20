package com.zgq.zgqaicodemother.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 精选应用缓存配置绑定。
 *
 * <p>除了绑定 {@code app.cache} 配置，还显式创建缓存专用的 {@link JsonMapper}：
 * 应用 HTTP 层把 Long 序列化为字符串（防止前端丢失雪花 id 精度），而缓存只需要在本服务内
 * 往返读写，使用单调的数值编码更稳定，也不会因为 HTTP 序列化规则调整而让缓存全部反序列化失败。</p>
 */
@Configuration
public class AppCacheConfig {

    /**
     * 绑定 {@code app.cache} 下的缓存开关、TTL 和精选列表限制参数。
     *
     * @return 缓存配置对象，字段均有可用默认值
     */
    @Bean
    @ConfigurationProperties(prefix = "app.cache")
    public AppCacheProperties appCacheProperties() {
        return new AppCacheProperties();
    }

    /**
     * 精选应用列表缓存。
     *
     * @param redisTemplate Spring Data Redis 模板，与应用其它缓存共用连接池
     * @param properties    缓存配置
     * @return 缓存组件
     */
    @Bean
    public GoodAppListCache goodAppListCache(StringRedisTemplate redisTemplate, AppCacheProperties properties) {
        return new GoodAppListCache(redisTemplate, JsonMapper.builder().build(), properties);
    }
}
