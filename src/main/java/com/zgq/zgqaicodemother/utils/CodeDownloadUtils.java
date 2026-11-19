package com.zgq.zgqaicodemother.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 应用代码下载工具。
 *
 * <p>只打包生成目录中的源码，按路径段过滤依赖目录、构建产物和敏感文件。过滤时不能简单地用
 * “路径中包含 build/dist”判断，否则 {@code src/builder/} 这类正常源码也会被误删；因此目录名
 * 必须与路径段完全匹配，文件名则支持环境变量前缀、日志和密钥后缀等规则。</p>
 *
 * <p>ZIP 条目始终使用相对于生成目录的路径，并统一使用 {@code /} 分隔，避免把服务器绝对路径
 * 或 Windows 反斜杠写入压缩包。遍历时不跟随符号链接，防止生成目录中的链接把服务器其他文件
 * 打入下载包。</p>
 */
public final class CodeDownloadUtils {

    /**
     * 不参与下载的目录：依赖目录可通过 npm install 还原，构建产物可重新生成，
     * IDE、版本控制和缓存目录属于本地环境信息。
     */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "node_modules", "dist", "build", "target", "out", "coverage",
            ".git", ".svn", ".hg", ".idea", ".vscode",
            ".next", ".nuxt", ".cache", ".turbo", ".parcel-cache", ".sass-cache");

    /**
     * 含系统信息或敏感配置的文件名，统一转小写后精确匹配。
     */
    private static final Set<String> EXCLUDED_FILE_NAMES = Set.of(
            ".ds_store", "thumbs.db", "desktop.ini",
            ".npmrc", ".yarnrc", ".git-credentials", ".netrc", "_netrc",
            ".bash_history", ".zsh_history");

    /**
     * 可能包含密钥、证书或运行日志的文件后缀。
     */
    private static final Set<String> EXCLUDED_FILE_SUFFIXES = Set.of(
            ".log", ".pem", ".key", ".p12", ".pfx");

    /**
     * 环境变量文件前缀，除 .env 外还会排除 .env.local、.env.production 等变体。
     */
    private static final String ENV_FILE_PREFIX = ".env";
    /**
     * 下载文件名中应用名称的最大长度（按 Unicode 码点计算），避免响应头过长。
     */
    private static final int MAX_BASE_NAME_LENGTH = 50;

    private CodeDownloadUtils() {
    }

    /**
     * 收集生成目录中允许下载的普通文件，并按 ZIP 条目名排序。
     *
     * @param sourceRoot 应用生成目录
     * @return 可下载文件列表；目录不存在时返回空列表
     * @throws IOException 遍历目录失败
     */
    public static List<Path> collectDownloadableFiles(Path sourceRoot) throws IOException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path root = sourceRoot.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    // 不跟随符号链接，避免链接目标位于生成目录之外。
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.normalize().startsWith(root))
                    .filter(path -> isDownloadable(root.relativize(path)))
                    // 固定条目顺序，便于排查问题和编写稳定测试。
                    .sorted(Comparator.comparing(path -> toZipEntryName(root.relativize(path))))
                    .toList();
        }
    }

    /**
     * 判断相对路径是否应该打包到下载文件中。
     *
     * @param relativePath 相对于生成目录的路径
     * @return 是源码文件时返回 true
     */
    public static boolean isDownloadable(Path relativePath) {
        if (relativePath == null || relativePath.isAbsolute() || relativePath.getNameCount() == 0) {
            return false;
        }
        int nameCount = relativePath.getNameCount();
        for (int index = 0; index < nameCount; index++) {
            String segment = relativePath.getName(index).toString();
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
            String normalized = segment.toLowerCase(Locale.ROOT);
            // 除最后一段外，其余路径段都是目录；只按完整路径段判断，避免误伤 builder 等源码目录。
            if (index < nameCount - 1 && EXCLUDED_DIRECTORIES.contains(normalized)) {
                return false;
            }
        }
        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        // 传入的路径本身可能就是构建目录或依赖目录。
        if (EXCLUDED_DIRECTORIES.contains(fileName)) {
            return false;
        }
        return !isExcludedFile(fileName);
    }

    /**
     * 构造下载文件名，例如 {@code 我的作品_123.zip}。
     *
     * <p>应用名称来自用户输入，可能包含引号、换行、路径分隔符等会破坏响应头的字符，
     * 因此先替换危险字符并限制长度。名称无效时使用 app 作为兜底。</p>
     *
     * @param appName 应用名称，允许为空
     * @param appId   应用 id
     * @return 安全的 ZIP 文件名
     */
    public static String buildDownloadFileName(String appName, long appId) {
        String source = appName == null ? "" : appName.trim();
        StringBuilder safeName = new StringBuilder();
        int codePoints = 0;
        for (int index = 0; index < source.length() && codePoints < MAX_BASE_NAME_LENGTH; ) {
            int codePoint = source.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || isUnsafeFileNameCharacter(codePoint)) {
                safeName.append('_');
            } else {
                safeName.appendCodePoint(codePoint);
            }
            codePoints++;
        }
        // Windows 不允许文件名以点或空格结尾，去掉后仍为空则使用兜底名称。
        String normalizedName = safeName.toString().replaceAll("[. ]+$", "");
        if (normalizedName.isBlank()) {
            normalizedName = "app";
        }
        return normalizedName + "_" + appId + ".zip";
    }

    /**
     * 把相对路径转换为 ZIP 条目名，统一使用正斜杠并拒绝越界路径。
     *
     * @param relativePath 相对于生成目录的路径
     * @return 适用于 ZIP 的条目名
     */
    public static String toZipEntryName(Path relativePath) {
        if (relativePath == null || relativePath.getNameCount() == 0) {
            throw new IllegalArgumentException("ZIP 条目路径不能为空");
        }
        Path normalized = relativePath.normalize();
        String entryName = normalized.toString();
        if (entryName.isEmpty() || normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("ZIP 条目路径不能跳出应用目录");
        }
        return entryName.replace('\\', '/');
    }

    /**
     * 把指定文件写入 ZIP 输出流。压缩流关闭时会关闭传入的输出流。
     *
     * @param sourceRoot   应用生成目录
     * @param files        待打包文件，必须位于生成目录内
     * @param outputStream ZIP 输出目标，通常是 HTTP 响应输出流
     * @throws IOException 读文件或写压缩流失败
     */
    public static void writeZip(Path sourceRoot, List<Path> files, OutputStream outputStream) throws IOException {
        if (sourceRoot == null || outputStream == null) {
            throw new IllegalArgumentException("下载目录和输出流不能为空");
        }
        Path root = sourceRoot.toAbsolutePath().normalize();
        try (ZipOutputStream zipOutput = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                Path relativePath = root.relativize(file.toAbsolutePath().normalize());
                // 再次校验，防止调用方传入生成目录之外的文件。
                if (!isDownloadable(relativePath)) {
                    continue;
                }
                zipOutput.putNextEntry(new ZipEntry(toZipEntryName(relativePath)));
                Files.copy(file, zipOutput);
                zipOutput.closeEntry();
            }
        }
    }

    /**
     * 环境变量、日志和密钥文件不进入下载包。
     */
    private static boolean isExcludedFile(String fileName) {
        if (EXCLUDED_FILE_NAMES.contains(fileName) || isEnvFile(fileName)) {
            return true;
        }
        return EXCLUDED_FILE_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    /**
     * 识别 .env、.env.local 以及 app.env、app.env.local 等环境变量文件。
     */
    private static boolean isEnvFile(String fileName) {
        return fileName.startsWith(ENV_FILE_PREFIX)
                || fileName.endsWith(".env")
                || fileName.contains(".env.");
    }

    /**
     * 判断字符是否不能出现在下载文件名中（Windows 保留字符及路径分隔符）。
     */
    private static boolean isUnsafeFileNameCharacter(int codePoint) {
        return codePoint == '"' || codePoint == '\\' || codePoint == '/'
                || codePoint == ':' || codePoint == '*' || codePoint == '?'
                || codePoint == '<' || codePoint == '>' || codePoint == '|';
    }
}
