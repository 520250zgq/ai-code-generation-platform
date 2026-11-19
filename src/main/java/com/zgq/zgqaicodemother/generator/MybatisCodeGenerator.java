package com.zgq.zgqaicodemother.generator;

import cn.hutool.setting.yaml.YamlUtil;
import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Map;

public class MybatisCodeGenerator {

    // 需要生成的表名
    private static final String[] TABLE_NAMES = {"chat_history"};

    /**
     * 读取 application.yml 数据源并生成指定表的 MyBatis-Flex 代码。
     */
    public static void main(String[] args) {
        // 获取数据源信息
        // Load as a map. YamlUtil.loadByPath(String) performs an unsafe Dict cast
        // with recent SnakeYAML versions and fails before generation starts.
        Map<?, ?> rootConfig = YamlUtil.loadByPath("application.yml", Map.class);
        Object springConfig = rootConfig.get("spring");
        Map<?, ?> spring = asMap(springConfig, "spring");
        Map<?, ?> dataSourceConfig = asMap(spring.get("datasource"), "spring.datasource");
        String url = requireProperty(dataSourceConfig, "url");
        String username = requireProperty(dataSourceConfig, "username");
        String password = requireProperty(dataSourceConfig, "password");
        // 配置数据源
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(url);
            dataSource.setUsername(username);
            dataSource.setPassword(password);

            // 创建配置内容
            GlobalConfig globalConfig = createGlobalConfig();

            // 通过 datasource 和 globalConfig 创建代码生成器
            Generator generator = new Generator(dataSource, globalConfig);

            // 生成代码
            generator.generate();
        }
    }

    /**
     * 读取必填数据源属性，缺失时给出可定位的配置错误。
     */
    private static String requireProperty(Map<?, ?> config, String name) {
        Object value = config.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("spring.datasource." + name + " is missing from application.yml");
        }
        return String.valueOf(value);
    }

    /**
     * 校验 YAML 节点是对象，避免后续类型转换出现难以定位的空指针。
     */
    private static Map<?, ?> asMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(path + " is missing or not an object in application.yml");
        }
        return map;
    }

    /**
     * 创建生成器配置：包名、目标表、逻辑删除字段和分层代码开关。
     */
    public static GlobalConfig createGlobalConfig() {
        // 创建配置内容
        GlobalConfig globalConfig = new GlobalConfig();

        // 设置根包，建议先生成到一个临时目录下，生成代码后，再移动到项目目录下
        globalConfig.getPackageConfig()
                .setBasePackage("com.zgq.zgqcodemother.genresult");

        // 设置表前缀和只生成哪些表，setGenerateTable 未配置时，生成所有表
        globalConfig.getStrategyConfig()
                .setGenerateTable(TABLE_NAMES)
                // 设置逻辑删除的默认字段名称
                .setLogicDeleteColumn("isDelete");

        // 设置生成 entity 并启用 Lombok
        globalConfig.enableEntity()
                .setWithLombok(true)
                .setJdkVersion(21);

        // 设置生成 mapper
        globalConfig.enableMapper();
        globalConfig.enableMapperXml();

        // 设置生成 service
        globalConfig.enableService();
        globalConfig.enableServiceImpl();

        // 设置生成 controller
        globalConfig.enableController();

        // 设置生成时间和字符串为空，避免多余的代码改动
        globalConfig.getJavadocConfig()
                .setAuthor("<a href=\"https://github.com/liyupi\">程序员鱼皮</a>")
                .setSince("");
        return globalConfig;
    }
}

