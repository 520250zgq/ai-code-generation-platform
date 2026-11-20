package com.zgq.zgqaicodemother.preview;

/**
 * 生成站点预览状态。
 *
 * <p>区分“目录不存在”和“正在构建”很重要：前者需要提示用户先生成，
 * 后者需要前端轮询等待，而不是直接展示一个空页面。</p>
 */
public enum PreviewState {

    /**
     * 还没有任何可预览的产物。
     */
    MISSING,

    /**
     * 代码已生成，Vue 工程正在安装依赖和打包。
     */
    BUILDING,

    /**
     * 最新版本已就绪，可以展示。
     */
    READY,

    /**
     * 最新版本构建失败，磁盘上可能仍是上一版产物。
     */
    FAILED;

    /**
     * 按名称解析状态；未知或空值按 {@link #MISSING} 处理，避免脏数据导致接口失败。
     *
     * @param name 状态名称
     * @return 预览状态
     */
    public static PreviewState parse(String name) {
        if (name == null || name.isBlank()) {
            return MISSING;
        }
        for (PreviewState state : values()) {
            if (state.name().equalsIgnoreCase(name.trim())) {
                return state;
            }
        }
        return MISSING;
    }
}
