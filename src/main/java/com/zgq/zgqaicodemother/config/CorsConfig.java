package com.zgq.zgqaicodemother.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 允许前端跨域访问并携带 Session Cookie，同时放行预览探活所需的 HEAD 请求。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 注册全局跨域规则，覆盖前后端分离联调和生成站点预览场景。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns("*")
                // 预览页面通过 HEAD 探测文件是否存在，必须与 GET 一并允许跨域。
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}

