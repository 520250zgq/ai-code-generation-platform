package com.zgq.zgqaicodemother.cache;

import lombok.Data;

import java.time.Duration;

/**
 * 主页精选应用列表的 Redis 缓存配置，统一绑定在 {@code app.cache} 前缀下。
 *
 * <p>缓存只保存展示所需的 {@link com.zgq.zgqaicodemother.model.vo.AppVO} 分页数据，
 * 不直接缓存数据库实体或 MyBatis-Flex 的 {@code Page}，避免框架字段变化后旧缓存无法反序列化。
 * 时间配置建议显式带单位，例如 {@code 60s}、{@code 2m}。</p>
 */
@Data
public class AppCacheProperties {

    /**
     * 缓存总开关。关闭后 {@code GoodAppListCache} 不读写 Redis，所有请求直接回源数据库。
     *
     * <p>适合压测、Redis 维护或排查数据不一致时快速关闭，无需回滚代码；
     * 生产环境建议保持开启，仅在故障时通过配置中心临时关闭。</p>
     */
    private boolean enabled = true;

    /**
     * 默认缓存过期时间。某一类缓存没有单独配置 TTL 时使用。
     *
     * <p>默认 60 秒，在首页读多写少和精选数据实时性之间折中。调大能提高命中率、降低数据库压力，
     * 但管理员修改精选后最长要等一个 TTL 才能看到（除非主动调用 {@code invalidate()}）；
     * 调小则数据更实时，但数据库压力会上升。</p>
     */
    private Duration defaultTtl = Duration.ofSeconds(60);

    /**
     * 所有缓存 key 的公共前缀，默认与应用其它 Redis key 保持同一命名空间。
     *
     * <p>多环境共用同一个 Redis 时应加入环境标识，例如
     * {@code zgq:ai-code-mother:test:cache:}，避免测试数据污染生产缓存。
     * 前缀末尾的冒号会自动补齐，无需手工维护。</p>
     */
    private String keyPrefix = "zgq:ai-code-mother:cache:";

    /**
     * 主页精选应用列表的专属配置。与总开关同时为 true 时才会读写该列表的缓存。
     */
    private GoodAppList goodAppList = new GoodAppList();

    /**
     * 精选应用列表缓存参数。
     *
     * <p>首页只允许查询前若干页且每页不超过 20 条，限制缓存范围可以避免深分页和爬虫
     * 把 Redis 当成数据库使用，也能控制单页 value 的大小。</p>
     */
    @Data
    public static class GoodAppList {

        /**
         * 精选列表缓存的独立开关。关闭后只有该列表回源数据库，不影响其它缓存。
         */
        private boolean enabled = true;

        /**
         * 精选列表缓存 TTL，默认 60 秒。
         *
         * <p>首页列表允许短暂陈旧；如果业务要求管理员调整优先级后立即生效，应保持较短 TTL，
         * 并在应用新增、修改、删除、部署封面更新后主动调用 {@code invalidate()}。</p>
         */
        private Duration ttl = Duration.ofSeconds(60);

        /**
         * 允许缓存的最大页码（含），默认 50。
         *
         * <p>超过该页码的深分页直接回源，避免低频请求占用大量 Redis key 和内存；
         * 可根据真实访问分布和 Redis 容量调整，不建议无上限放开。</p>
         */
        private int maxPageNum = 50;

        /**
         * 允许缓存的最大每页条数（含），默认 20，与普通用户接口的每页上限一致。
         *
         * <p>调大后单页 value 更大，仍需受 512KB 的单 value 上限保护；
         * 该值应小于或等于 Controller 允许的每页数量，避免缓存永远不会命中。</p>
         */
        private int maxPageSize = 20;

        /**
         * 允许缓存的应用名称关键词最大长度（含），默认 64。
         *
         * <p>超长关键词通常来自异常请求或爬虫，直接回源可以避免 key 空间被污染；
         * 关键词本身不会写入 key，只会写入 SHA-256 摘要的前 16 位。</p>
         */
        private int maxAppNameLength = 64;
    }
}
