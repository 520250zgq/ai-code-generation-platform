package com.zgq.zgqaicodemother.workflow.model;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼接后的项目代码。
 *
 * <p>代码质检工具遍历生成目录后得到的只读快照：既保留按文件拆分的内容（用于静态检查
 * 和定位问题），也保留已带文件头的拼接文本（用于提示词）。快照不进入工作流上下文，
 * 只在一次节点执行内使用，避免状态对象过大。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 参与检查的文件，按相对路径排序。
     */
    @Builder.Default
    private List<FileEntry> files = new ArrayList<>();

    /**
     * 带“文件: xxx”标题的拼接文本，最多 {@code maxTotalChars} 个字符。
     */
    private String text;

    /**
     * 是否因为文件数量或字符数上限只读取了部分内容。
     */
    private boolean truncated;

    /**
     * 因为读取失败或不支持的扩展名而跳过的文件数。
     */
    private int skippedFiles;

    /**
     * 读取过程中的非致命告警。
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * 是否没有可检查的代码。
     *
     * @return 文件列表为空时返回 true
     */
    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }

    /**
     * 实际读取的代码字符数。
     *
     * @return 所有文件内容长度之和
     */
    public int totalChars() {
        if (files == null) {
            return 0;
        }
        return files.stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(entry -> entry.getContent() == null ? 0 : entry.getContent().length())
                .sum();
    }

    /**
     * 按相对路径精确查找文件，路径统一使用 / 分隔。
     *
     * @param relativePath 相对路径
     * @return 文件内容，不存在时返回 null
     */
    public FileEntry file(String relativePath) {
        if (StrUtil.isBlank(relativePath) || files == null) {
            return null;
        }
        String normalized = relativePath.replace('\\', '/');
        return files.stream()
                .filter(entry -> entry != null && normalized.equals(entry.getPath()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 文件相对路径列表。
     *
     * @return 文件路径
     */
    public List<String> filePaths() {
        if (files == null) {
            return List.of();
        }
        return files.stream()
                .filter(java.util.Objects::nonNull)
                .map(FileEntry::getPath)
                .toList();
    }

    /**
     * 单个代码文件。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileEntry implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 相对于工程根目录的路径，统一使用 / 分隔。
         */
        private String path;

        /**
         * 文件内容，遇到长度上限时末尾会带截断标记。
         */
        private String content;

        /**
         * 该文件内容是否被截断。
         */
        private boolean truncated;
    }
}
