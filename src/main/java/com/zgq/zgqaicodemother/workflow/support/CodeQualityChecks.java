package com.zgq.zgqaicodemother.workflow.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.model.ProjectCode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码确定性静态检查。
 *
 * <p>AI 复核可能漏判或受上下文长度影响，因此质检节点在调用模型之前先做一轮可复现的
 * 检查：文件是否完整、本地资源与模块引用是否存在、Vue 工程是否具备构建必需的文件和配置、
 * 是否残留 Markdown 代码围栏。静态检查发现的问题一票否决，同时也会作为线索交给 AI。</p>
 *
 * <p>这里只报告“确定存在问题”的情况，不做样式、性能和可访问性判断，避免把正常代码
 * 误判为错误导致无意义的重新生成。</p>
 */
public final class CodeQualityChecks {

    /**
     * HTML 中的本地资源引用。
     */
    private static final Pattern HTML_REFERENCE = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * CSS 中的 url(...) 引用。
     */
    private static final Pattern CSS_REFERENCE = Pattern.compile(
            "url\\(\\s*['\"]?([^'\")\\s]+)\\s*['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * JS / TS / Vue 中的 import、require 路径。
     */
    private static final Pattern MODULE_REFERENCE = Pattern.compile(
            "(?:\\bfrom\\s*|\\bimport\\s*\\(?\\s*|\\brequire\\s*\\(\\s*)['\"]([^'\"]+)['\"]");

    /**
     * 解析省略扩展名的导入时依次尝试的扩展名。
     */
    private static final List<String> RESOLVE_EXTENSIONS = List.of(
            ".js", ".ts", ".mjs", ".cjs", ".jsx", ".tsx", ".vue", ".json", ".css", ".scss", ".less");

    /**
     * 单个文件最多报告的问题数，避免一个文件刷爆错误列表。
     */
    private static final int MAX_ISSUES_PER_FILE = 5;

    private CodeQualityChecks() {
    }

    /**
     * 执行静态检查。
     *
     * @param code      项目代码快照
     * @param type      生成模式
     * @param maxErrors 最多保留的错误条数
     * @return 确定存在的问题，空列表表示通过
     */
    public static List<String> check(ProjectCode code, CodeGenTypeEnum type, int maxErrors) {
        List<String> errors = new ArrayList<>();
        if (code == null || code.isEmpty()) {
            errors.add("未找到可检查的代码文件，代码可能生成失败或被清空");
            return errors;
        }
        checkCommon(code, errors);
        if (type == CodeGenTypeEnum.MULTI_FILE) {
            checkStaticSiteReferences(code, errors);
        }
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            checkVueProject(code, errors);
        }
        return capErrors(errors, maxErrors);
    }

    /**
     * 所有生成模式共用的完整性检查。
     */
    private static void checkCommon(ProjectCode code, List<String> errors) {
        ProjectCode.FileEntry indexHtml = findIndexHtml(code);
        if (indexHtml == null) {
            errors.add("缺少 index.html 入口文件");
        } else if (StrUtil.isBlank(indexHtml.getContent())) {
            errors.add("index.html 内容为空，页面会白屏");
        } else if (!endsWithHtmlTag(indexHtml.getContent())) {
            errors.add("index.html 缺少 </html> 结束标签，文件可能被截断");
        }
        for (ProjectCode.FileEntry file : code.getFiles()) {
            if (file == null || StrUtil.isBlank(file.getContent())) {
                continue;
            }
            if (file.getContent().contains("```")) {
                errors.add(file.getPath() + "：文件中残留 Markdown 代码围栏（```），会导致语法错误");
            }
        }
    }

    /**
     * HTML / 多文件模式检查本地资源引用是否存在。
     */
    private static void checkStaticSiteReferences(ProjectCode code, List<String> errors) {
        ProjectCode.FileEntry indexHtml = findIndexHtml(code);
        if (indexHtml != null) {
            collectReferenceErrors(code, indexHtml, HTML_REFERENCE, errors);
        }
        for (ProjectCode.FileEntry file : code.getFiles()) {
            if (file == null || file.getPath() == null
                    || !file.getPath().toLowerCase(Locale.ROOT).endsWith(".css")) {
                continue;
            }
            collectReferenceErrors(code, file, CSS_REFERENCE, errors);
        }
    }

    /**
     * Vue 工程检查构建必需文件、package.json、@ 别名配置和模块导入路径。
     */
    private static void checkVueProject(ProjectCode code, List<String> errors) {
        ProjectCode.FileEntry packageJson = code.file("package.json");
        if (packageJson == null || StrUtil.isBlank(packageJson.getContent())) {
            errors.add("缺少 package.json，Vue 工程无法安装依赖和构建");
        } else {
            checkPackageJson(packageJson, errors);
        }
        if (findEntryModule(code) == null) {
            errors.add("缺少 src/main.js 或 src/main.ts 应用入口文件");
        }
        boolean hasViteConfig = code.file("vite.config.js") != null
                || code.file("vite.config.ts") != null
                || code.file("vite.config.mjs") != null;
        boolean usesAtAlias = false;
        for (ProjectCode.FileEntry file : code.getFiles()) {
            if (file == null || StrUtil.isBlank(file.getContent()) || !isModuleFile(file.getPath())) {
                continue;
            }
            Matcher matcher = MODULE_REFERENCE.matcher(file.getContent());
            int issues = 0;
            while (matcher.find() && issues < MAX_ISSUES_PER_FILE) {
                String specifier = matcher.group(1).trim();
                if (specifier.startsWith("@/")) {
                    usesAtAlias = true;
                }
                if (!isLocalSpecifier(specifier)) {
                    continue;
                }
                if (resolveModule(code, file.getPath(), specifier) == null) {
                    errors.add(file.getPath() + "：导入的模块 " + specifier + " 不存在，构建会失败");
                    issues++;
                }
            }
        }
        if (usesAtAlias && !hasViteConfig) {
            errors.add("代码使用了 @ 路径别名，但缺少 vite.config.js 配置，构建会失败");
        }
    }

    /**
     * package.json 的结构化检查。
     */
    private static void checkPackageJson(ProjectCode.FileEntry packageJson, List<String> errors) {
        JSONObject json;
        try {
            json = JSONUtil.parseObj(packageJson.getContent());
        } catch (RuntimeException e) {
            errors.add("package.json 不是合法的 JSON：" + safeMessage(e));
            return;
        }
        JSONObject scripts = json.getJSONObject("scripts");
        String buildScript = scripts == null ? null : scripts.getStr("build");
        if (StrUtil.isBlank(buildScript)) {
            errors.add("package.json 缺少 scripts.build，无法执行 npm run build");
        }
        boolean hasVue = containsKey(json.getJSONObject("dependencies"), "vue")
                || containsKey(json.getJSONObject("devDependencies"), "vue");
        if (!hasVue) {
            errors.add("package.json 未声明 vue 依赖，构建会失败");
        }
    }

    private static boolean containsKey(JSONObject object, String key) {
        return object != null && object.containsKey(key);
    }

    /**
     * 扫描 HTML / CSS 中引用的本地文件。
     */
    private static void collectReferenceErrors(ProjectCode code, ProjectCode.FileEntry file,
                                               Pattern pattern, List<String> errors) {
        Matcher matcher = pattern.matcher(file.getContent());
        Set<String> reported = new LinkedHashSet<>();
        int issues = 0;
        while (matcher.find() && issues < MAX_ISSUES_PER_FILE) {
            String reference = matcher.group(1).trim();
            if (!isLocalAsset(reference)) {
                continue;
            }
            String relative = stripQueryAndHash(reference);
            String baseDir = parentDirectory(file.getPath());
            boolean exists = existsInProject(code, relative.startsWith("/")
                    ? relative.substring(1)
                    : joinPath(baseDir, relative));
            if (!exists && reported.add(reference)) {
                errors.add(file.getPath() + "：引用的本地资源 " + reference + " 不存在");
                issues++;
            }
        }
    }

    /**
     * 判断资源引用是否指向工程内的本地文件。
     */
    private static boolean isLocalAsset(String reference) {
        if (StrUtil.isBlank(reference)) {
            return false;
        }
        String lower = reference.toLowerCase(Locale.ROOT);
        // 锚点、协议、模板变量和内联数据都不是本地文件。
        return !lower.startsWith("#")
                && !lower.startsWith("//")
                && !lower.startsWith("data:")
                && !lower.startsWith("mailto:")
                && !lower.startsWith("tel:")
                && !lower.startsWith("javascript:")
                && !lower.contains("://")
                && !reference.contains("${")
                && !reference.contains("{{")
                && !reference.contains("%");
    }

    /**
     * 判断模块说明符是否指向工程内文件。
     */
    private static boolean isLocalSpecifier(String specifier) {
        if (StrUtil.isBlank(specifier)) {
            return false;
        }
        String lower = specifier.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) {
            return false;
        }
        return specifier.startsWith("./") || specifier.startsWith("../") || specifier.startsWith("@/");
    }

    /**
     * 解析工程内模块路径，返回相对路径；不存在时返回 null。
     *
     * <p>支持 {@code @/} 别名（对应 src 目录）、相对路径、省略扩展名以及目录下的 index 文件。</p>
     */
    private static String resolveModule(ProjectCode code, String sourcePath, String specifier) {
        String cleaned = stripQueryAndHash(specifier).replace('\\', '/');
        String base;
        if (cleaned.startsWith("@/")) {
            base = "src/" + cleaned.substring(2);
        } else {
            String parent = parentDirectory(sourcePath);
            base = joinPath(parent, cleaned);
        }
        String normalized = normalizeRelative(base);
        if (normalized == null) {
            return null;
        }
        if (code.file(normalized) != null || containsFileIgnoringCase(code, normalized)) {
            return normalized;
        }
        for (String extension : RESOLVE_EXTENSIONS) {
            String candidate = normalized + extension;
            if (code.file(candidate) != null || containsFileIgnoringCase(code, candidate)) {
                return candidate;
            }
        }
        for (String extension : RESOLVE_EXTENSIONS) {
            String candidate = normalized + "/index" + extension;
            if (code.file(candidate) != null || containsFileIgnoringCase(code, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsFileIgnoringCase(ProjectCode code, String path) {
        for (String filePath : code.filePaths()) {
            if (path.equalsIgnoreCase(filePath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean existsInProject(ProjectCode code, String path) {
        String normalized = normalizeRelative(path);
        if (normalized == null) {
            return false;
        }
        return code.file(normalized) != null || containsFileIgnoringCase(code, normalized);
    }

    /**
     * 归一化相对路径，拒绝逃逸到工程目录之外的路径。
     */
    private static String normalizeRelative(String path) {
        if (StrUtil.isBlank(path)) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static String parentDirectory(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String joinPath(String parent, String child) {
        if (StrUtil.isBlank(parent)) {
            return child;
        }
        return parent + "/" + child;
    }

    private static String stripQueryAndHash(String reference) {
        int index = reference.length();
        int query = reference.indexOf('?');
        int hash = reference.indexOf('#');
        if (query >= 0) {
            index = Math.min(index, query);
        }
        if (hash >= 0) {
            index = Math.min(index, hash);
        }
        return reference.substring(0, index);
    }

    private static ProjectCode.FileEntry findIndexHtml(ProjectCode code) {
        ProjectCode.FileEntry entry = code.file("index.html");
        return entry != null ? entry : code.file("index.htm");
    }

    private static ProjectCode.FileEntry findEntryModule(ProjectCode code) {
        ProjectCode.FileEntry entry = code.file("src/main.js");
        if (entry != null) {
            return entry;
        }
        entry = code.file("src/main.ts");
        if (entry != null) {
            return entry;
        }
        return code.file("src/main.mjs");
    }

    private static boolean endsWithHtmlTag(String content) {
        String trimmed = content.trim().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("</html>");
    }

    private static boolean isModuleFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".mjs")
                || lower.endsWith(".cjs") || lower.endsWith(".jsx") || lower.endsWith(".tsx")
                || lower.endsWith(".vue");
    }

    private static List<String> capErrors(List<String> errors, int maxErrors) {
        int limit = maxErrors <= 0 ? 20 : maxErrors;
        if (errors.size() <= limit) {
            return errors;
        }
        List<String> capped = new ArrayList<>(errors.subList(0, limit));
        capped.add("问题数量超过上限，仅保留前 " + limit + " 条");
        return capped;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return StrUtil.blankToDefault(StrUtil.maxLength(message, 120), "JSON 解析失败");
    }
}
