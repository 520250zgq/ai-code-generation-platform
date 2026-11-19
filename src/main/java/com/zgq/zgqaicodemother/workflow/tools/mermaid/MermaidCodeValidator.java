package com.zgq.zgqaicodemother.workflow.tools.mermaid;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mermaid 代码安全校验与规范化。
 *
 * <p>Mermaid 代码由大模型生成，最终会交给一个带浏览器内核的 CLI 渲染。这里不做“相信模型”的
 * 假设：限制代码长度、图表类型、外部资源引用和危险指令，避免渲染器被用来访问内网或执行
 * 非图表内容。生产环境仍建议在独立进程/容器中运行 Mermaid CLI。</p>
 */
public final class MermaidCodeValidator {

    /**
     * 支持的 Mermaid 图表起始关键字。列表按小写比较。
     */
    private static final List<String> SUPPORTED_TYPES = List.of(
            "graph", "flowchart", "sequencediagram", "classdiagram", "statediagram",
            "erdiagram", "gantt", "pie", "mindmap", "timeline", "journey", "gitgraph",
            "quadrantchart", "requirementdiagram", "c4context", "c4container", "c4component",
            "c4dynamic", "c4deployment", "sankey-beta", "xychart-beta", "block-beta",
            "packet-beta", "architecture-beta", "kanban", "radar-beta", "treemap-beta", "zenuml");

    private static final Pattern INIT_DIRECTIVE = Pattern.compile("(?s)%%\\{.*?}%%");

    private static final Pattern CODE_FENCE = Pattern.compile("(?s)^\\s*```[a-zA-Z0-9_-]*\\s*\\n(.*?)\\n?```\\s*$");

    private static final Pattern YAML_FRONT_MATTER = Pattern.compile("(?s)^\\s*---\\s*\\n.*?\\n---\\s*\\n");

    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "<script", "<iframe", "<object", "<embed", "<img", "<link", "<style",
            "javascript:", "vbscript:", "file:", "data:", "onload=", "onerror=",
            "securitylevel", "click ", "callback");

    private MermaidCodeValidator() {
    }

    /**
     * 校验并返回规范化后的 Mermaid 代码。
     *
     * @param rawCode                模型生成的原始代码
     * @param maxLength              最大字符数
     * @param allowExternalResources 是否允许 http/https 外部资源
     * @return 去掉代码围栏、初始化指令后的代码
     * @throws IllegalArgumentException 代码不合法时抛出，消息可直接记录日志
     */
    public static String validate(String rawCode, int maxLength, boolean allowExternalResources) {
        String code = normalize(rawCode, maxLength);
        String lower = code.toLowerCase(Locale.ROOT);
        if (!allowExternalResources) {
            if (lower.contains("http://") || lower.contains("https://")) {
                throw new IllegalArgumentException("Mermaid 代码不允许引用外部资源");
            }
            for (String token : FORBIDDEN_TOKENS) {
                if (lower.contains(token)) {
                    throw new IllegalArgumentException("Mermaid 代码包含不支持的指令：" + token);
                }
            }
        } else {
            for (String token : List.of("<script", "<iframe", "javascript:", "vbscript:", "file:")) {
                if (lower.contains(token)) {
                    throw new IllegalArgumentException("Mermaid 代码包含不安全的指令：" + token);
                }
            }
        }
        if (lower.contains("securitylevel")) {
            throw new IllegalArgumentException("Mermaid 代码不允许修改安全级别");
        }
        if (code.lines().count() > 500) {
            throw new IllegalArgumentException("Mermaid 代码行数过多");
        }
        String firstKeyword = firstKeyword(code);
        boolean supported = SUPPORTED_TYPES.stream()
                .anyMatch(type -> firstKeyword.startsWith(type));
        if (!supported) {
            throw new IllegalArgumentException("不支持的 Mermaid 图表类型：" + firstKeyword);
        }
        return code;
    }

    /**
     * 清洗代码围栏、YAML front-matter 和 init 指令。规范化后仍为空时抛出异常。
     */
    static String normalize(String rawCode, int maxLength) {
        if (StrUtil.isBlank(rawCode)) {
            throw new IllegalArgumentException("Mermaid 代码不能为空");
        }
        String code = rawCode.trim();
        var fenceMatcher = CODE_FENCE.matcher(code);
        if (fenceMatcher.matches()) {
            code = fenceMatcher.group(1).trim();
        }
        code = YAML_FRONT_MATTER.matcher(code).replaceFirst("").trim();
        // init 指令可以修改安全级别/主题，统一移除，避免模型通过指令绕过限制。
        code = INIT_DIRECTIVE.matcher(code).replaceAll("").trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Mermaid 代码不能为空");
        }
        if (code.length() > maxLength) {
            throw new IllegalArgumentException("Mermaid 代码超过长度上限");
        }
        for (int i = 0; i < code.length(); i++) {
            char current = code.charAt(i);
            if (Character.isISOControl(current) && current != '\n' && current != '\r' && current != '\t') {
                throw new IllegalArgumentException("Mermaid 代码包含非法控制字符");
            }
        }
        return code;
    }

    /**
     * 取第一行有效语句的首个关键字，忽略空行和注释行。
     */
    private static String firstKeyword(String code) {
        for (String line : code.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("%%")) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            String keyword = space < 0 ? trimmed : trimmed.substring(0, space);
            return keyword.toLowerCase(Locale.ROOT);
        }
        return "";
    }
}
