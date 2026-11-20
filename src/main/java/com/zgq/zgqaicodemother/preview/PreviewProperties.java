package com.zgq.zgqaicodemother.preview;

import lombok.Data;

import java.time.Duration;

/**
 * 生成站点预览状态配置。
 *
 * <p>预览状态解决的是“用户生成完成后能不能立刻看到最新效果”的问题：静态模式文件一落盘就可预览；
 * Vue 工程还需要 {@code npm install && npm run build}，构建期间磁盘上可能仍是上一版 dist，
 * 直接访问只会看到旧页面。因此需要用版本号和构建状态告诉前端“现在该不该刷新、
 * 刷新后是不是最新版本”。</p>
 */
@Data
public class PreviewProperties {

    /**
     * 是否启用预览状态跟踪。关闭后前端仍可用原有方式预览，但拿不到构建进度。
     */
    private boolean enabled = true;

    /**
     * 版本号与状态在 Redis 中的保留时间。应大于用户可能停留的页面时长，
     * 版本号过期后从 0 重新计数，仅影响 iframe 的缓存刷新参数，不影响正确性。
     */
    private Duration ttl = Duration.ofDays(7);

    /**
     * 状态 key 前缀，按应用维度追加 appId。
     */
    private String keyPrefix = "zgq:ai-code-mother:preview:";

    /**
     * 本地降级缓存的最大条目数。Redis 不可用时状态退化为单实例内存记录，
     * 这里限制上限避免无界增长。
     */
    private int maxLocalEntries = 5000;

    /**
     * 构建中的提示文案。
     */
    private String buildingMessage = "正在构建最新版本，请稍候，完成后会自动刷新";

    /**
     * 还没有任何生成结果时的提示文案。
     */
    private String missingMessage = "还没有可预览的页面，先让 AI 生成一个网站吧";

    /**
     * 最新版本构建失败时的提示文案。
     */
    private String failedMessage = "最新版本构建失败，当前展示的可能不是最新效果";
}
