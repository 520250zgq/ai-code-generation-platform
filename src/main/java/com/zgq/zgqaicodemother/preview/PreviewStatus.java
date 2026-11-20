package com.zgq.zgqaicodemother.preview;

/**
 * 对外返回的预览状态快照。
 *
 * <p>{@code previewPath} 只包含相对路径（例如 {@code static/vue_project_123/}），
 * 由前端拼接预览根地址，避免后端硬编码协议、域名和 context-path。</p>
 *
 * @param appId         应用 id
 * @param codeGenType   代码生成类型
 * @param state         预览状态
 * @param revision      版本号；每次生成或构建成功都会递增，用于 iframe 强制刷新
 * @param ready         是否可以直接展示最新版本
 * @param previewPath   预览相对路径，不含查询参数；尚无产物时为 null
 * @param message       面向用户的状态提示
 * @param updatedAtMillis 状态最后更新时间（毫秒时间戳），没有记录时为 0
 */
public record PreviewStatus(Long appId,
                            String codeGenType,
                            PreviewState state,
                            long revision,
                            boolean ready,
                            String previewPath,
                            String message,
                            long updatedAtMillis) {
}
