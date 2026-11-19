package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.tool.ToolExecution;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件类 AI 工具的公共基类。
 *
 * <p>所有文件工具共享同一套安全约束：只能访问当前应用 {@code vue_project_{appId}} 工程目录内的
 * 相对路径，拒绝绝对路径、盘符和 {@code ..} 穿越；返回给模型的结果只能包含相对路径。
 * 把这些逻辑集中在基类中，避免每个工具重复实现、也避免某个工具漏掉安全检查。</p>
 *
 * <p>子类只负责实现具体文件操作和 {@code @Tool} 描述，流式进度统一由
 * {@link #generateToolExecutedResult(ToolExecution)} 提取第一行生成。</p>
 */
public abstract class AbstractFileTool implements BaseTool {

    /**
     * 单个文件内容的长度上限，避免模型异常输出占满磁盘或撑爆上下文。
     */
    protected static final int MAX_CONTENT_LENGTH = 1_000_000;

    /**
     * 流式进度中单条工具信息的最大长度。
     */
    protected static final int MAX_PROGRESS_LENGTH = 200;

    /**
     * 当前应用的 Vue 工程根目录。
     *
     * @param appId 应用 id
     * @return 归一化后的绝对路径，仅用于服务端读写，不能返回给模型
     */
    public static Path projectRoot(Long appId) {
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName(appId))
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 工程目录名，与代码保存器的目录约定保持一致。
     *
     * @param appId 应用 id
     * @return 形如 {@code vue_project_123} 的目录名
     */
    protected static String projectDirName(Long appId) {
        return CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
    }

    /**
     * 校验应用 id 是否可用于定位工程目录。
     *
     * @param appId 应用 id
     * @return 合法返回 true
     */
    protected static boolean isValidAppId(Long appId) {
        return appId != null && appId > 0;
    }

    /**
     * 校验并规范化模型传入的文件相对路径。
     *
     * @param rawPath 模型传入的原始路径
     * @return 规范化后的相对路径；非法时返回 null
     */
    protected static String normalizeRelativePath(String rawPath) {
        if (StrUtil.isBlank(rawPath)) {
            return null;
        }
        // 兼容模型使用 Windows 反斜杠的情况，统一按 / 处理后再做安全检查。
        String path = rawPath.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                || path.contains(":") || path.indexOf('\0') >= 0) {
            return null;
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        return path;
    }

    /**
     * 校验并规范化模型传入的目录相对路径。
     *
     * <p>与文件路径不同，空字符串和 {@code .} 都表示工程根目录，末尾斜杠会被忽略。</p>
     *
     * @param rawPath 模型传入的原始目录路径
     * @return 规范化后的相对目录；根目录返回空字符串，非法时返回 null
     */
    protected static String normalizeRelativeDirectory(String rawPath) {
        if (StrUtil.isBlank(rawPath)) {
            return "";
        }
        String path = rawPath.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (".".equals(path)) {
            return "";
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.startsWith("/") || path.contains(":") || path.indexOf('\0') >= 0) {
            return null;
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        return path;
    }

    /**
     * 模型偶尔会把工程目录名也带上；只有与当前 appId 完全匹配时才剥离，避免访问嵌套目录。
     *
     * @param normalizedPath 已规范化的相对路径
     * @param appId          应用 id
     * @return 去掉工程目录前缀后的相对路径
     */
    protected static String stripProjectDirPrefix(String normalizedPath, Long appId) {
        String projectDirName = projectDirName(appId);
        if (normalizedPath.equals(projectDirName)) {
            return "";
        }
        if (normalizedPath.startsWith(projectDirName + "/")) {
            return normalizedPath.substring(projectDirName.length() + 1);
        }
        return normalizedPath;
    }

    /**
     * 把相对路径解析为工程内的真实路径。
     *
     * <p>归一化后必须仍位于当前应用工程目录内，防止 {@code ../} 或特殊路径跳出沙箱。</p>
     *
     * @param appId          应用 id
     * @param normalizedPath 已规范化的相对路径，空字符串表示工程根目录
     * @param allowRoot      是否允许指向工程根目录
     * @return 工程内路径；越界或路径非法时返回 null
     */
    protected static Path resolveProjectPath(Long appId, String normalizedPath, boolean allowRoot) {
        Path projectRoot = projectRoot(appId);
        Path target;
        try {
            target = projectRoot.resolve(normalizedPath).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (target.equals(projectRoot)) {
            return allowRoot ? target : null;
        }
        return target.startsWith(projectRoot) ? target : null;
    }

    /**
     * 只保留工具结果的第一行，并截断过长内容，避免把完整文件内容推到聊天记录里。
     *
     * @param result 工具返回值
     * @return 可展示的 Markdown 进度
     */
    protected String progress(String icon, String result) {
        String firstLine = StrUtil.blankToDefault(result, "工具执行完成")
                .replace('\\', '/')
                .lines()
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .map(String::trim)
                .orElse("工具执行完成");
        // 失败结果使用警示图标，用户能一眼看出这一步没有成功。
        String actualIcon = firstLine.contains("失败") ? "⚠️" : icon;
        return "\n\n> " + actualIcon + " " + StrUtil.maxLength(firstLine, MAX_PROGRESS_LENGTH) + "\n\n";
    }
}
