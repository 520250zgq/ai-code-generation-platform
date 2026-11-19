package com.zgq.zgqaicodemother.config;

import com.zgq.zgqaicodemother.manager.CosManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置。
 *
 * <p>只有密钥和存储桶都配置完整时才会创建 COS 客户端；未配置时应用仍可启动，截图上传会返回明确的
 * 业务异常，不影响部署等主流程。</p>
 */
@Configuration
public class CosConfig {

    /**
     * 绑定 {@code app.cos} 前缀下的对象存储配置。
     */
    @Bean
    @ConfigurationProperties(prefix = "app.cos")
    public CosProperties cosProperties() {
        return new CosProperties();
    }

    /**
     * COS 上传管理器，负责客户端生命周期和截图对象上传。
     */
    @Bean
    public CosManager cosManager(CosProperties cosProperties) {
        return new CosManager(cosProperties);
    }
}
