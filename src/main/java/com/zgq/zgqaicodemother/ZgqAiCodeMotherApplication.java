package com.zgq.zgqaicodemother;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@SpringBootApplication
@MapperScan("com.zgq.zgqaicodemother.mapper")
public class ZgqAiCodeMotherApplication {

    /**
     * 启动 Spring Boot 应用，加载 Mapper 扫描、AOP 代理和全部业务组件。
     */
    public static void main(String[] args) {
        SpringApplication.run(ZgqAiCodeMotherApplication.class, args);
    }

}
