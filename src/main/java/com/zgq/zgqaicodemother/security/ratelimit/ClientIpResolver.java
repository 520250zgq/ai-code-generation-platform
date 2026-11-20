package com.zgq.zgqaicodemother.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;

/**
 * 客户端 IP 解析工具。
 *
 * <p>限流 key 必须来自可信且格式受控的客户端标识。转发头由客户端可控，如果直接把原始值
 * 拼进 Redis key，攻击者可以塞入超长字符串、逗号分隔的多级代理链或控制字符，造成 key
 * 膨胀、日志注入或绕过按 IP 限流。因此这里只取第一个非空片段，去掉端口，限制长度，
 * 并按字符白名单清洗；任何一步不通过都退回 {@code getRemoteAddr()}，最终兜底为
 * {@code unknown}。</p>
 *
 * <p>解析器不校验完整的 IPv4 网段范围，也不做 DNS 解析：限流只需要稳定的来源标识，
 * 不需要建立网络连接或引入额外的解析开销。只有在应用前方存在会覆盖转发头的可信代理时，
 * 才应配置并信任 {@code trustedProxyHeader}。</p>
 */
public final class ClientIpResolver {

    private static final String DEFAULT_TRUSTED_PROXY_HEADER = "X-Forwarded-For";

    private static final String REAL_IP_HEADER = "X-Real-IP";

    private static final String UNKNOWN = "unknown";

    /**
     * IP 标识的最大长度。IPv6 文本表示通常不超过 45 个字符，64 为带 zone id 等扩展信息留出余量。
     */
    private static final int MAX_IP_LENGTH = 64;

    private ClientIpResolver() {
        // 静态工具类不允许实例化。
    }

    /**
     * 使用默认的 {@code X-Forwarded-For} 转发头解析客户端 IP。
     *
     * @param request 当前 HTTP 请求，允许为 null
     * @return 清洗后的客户端 IP；无法解析时返回 {@code unknown}，绝不返回 null
     */
    public static String resolve(HttpServletRequest request) {
        return resolve(request, DEFAULT_TRUSTED_PROXY_HEADER);
    }

    /**
     * 按指定转发头解析客户端 IP。
     *
     * <p>优先级为：指定转发头的第一个非空片段、{@code X-Real-IP}、{@code getRemoteAddr()}。
     * 如果指定头或 {@code X-Real-IP} 的值包含逗号、空格、换行或其它不允许的字符，会丢弃该
     * 候选值并继续回退，而不是把原始值写入 Redis key。</p>
     *
     * @param request            当前 HTTP 请求，允许为 null
     * @param trustedProxyHeader 可信代理写入客户端 IP 的头名称，空白时使用默认值
     * @return 清洗后的客户端 IP；无法解析时返回 {@code unknown}，绝不返回 null
     */
    public static String resolve(HttpServletRequest request, String trustedProxyHeader) {
        if (request == null) {
            return UNKNOWN;
        }
        String headerName = trustedProxyHeader == null || trustedProxyHeader.isBlank()
                ? DEFAULT_TRUSTED_PROXY_HEADER
                : trustedProxyHeader.trim();
        // 同一转发头可能出现多个值或多个逗号分隔的代理链，只信任最左侧的客户端来源。
        String sanitized = sanitize(firstNonBlankSegment(request, headerName));
        if (sanitized != null) {
            return sanitized;
        }
        // X-Real-IP 是常见的单值转发头；若指定头本来就是它，上面的读取已经覆盖，避免重复读取。
        if (!REAL_IP_HEADER.equalsIgnoreCase(headerName)) {
            sanitized = sanitize(firstNonBlankSegment(request, REAL_IP_HEADER));
            if (sanitized != null) {
                return sanitized;
            }
        }
        // 没有可信转发头或转发值非法时，使用容器解析出的连接地址作为最后兜底。
        sanitized = sanitize(request.getRemoteAddr());
        return sanitized == null ? UNKNOWN : sanitized;
    }

    /**
     * 在请求头的多个值和逗号分隔片段中，寻找第一个非空片段。
     */
    private static String firstNonBlankSegment(HttpServletRequest request, String headerName) {
        Enumeration<String> headerValues = request.getHeaders(headerName);
        if (headerValues == null) {
            return null;
        }
        while (headerValues.hasMoreElements()) {
            String headerValue = headerValues.nextElement();
            if (headerValue == null) {
                continue;
            }
            String[] segments = headerValue.split(",", -1);
            for (String segment : segments) {
                if (!segment.isBlank()) {
                    return segment;
                }
            }
        }
        return null;
    }

    /**
     * 清洗并校验单个 IP 候选值，失败时返回 null 让调用方继续回退。
     */
    private static String sanitize(String rawIp) {
        if (rawIp == null) {
            return null;
        }
        String candidate = rawIp.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.startsWith("[")) {
            // 带方括号的 IPv6 可能形如 [2001:db8::1]:443；只保留方括号内部地址。
            int closingBracket = candidate.indexOf(']');
            if (closingBracket <= 1) {
                return null;
            }
            String portPart = candidate.substring(closingBracket + 1);
            if (!portPart.isEmpty()) {
                if (!portPart.startsWith(":") || !isAsciiDigits(portPart.substring(1))) {
                    return null;
                }
            }
            candidate = candidate.substring(1, closingBracket);
        } else if (countColons(candidate) == 1) {
            // 无方括号且只有一个冒号时只可能是 IPv4:port；端口后的内容必须全是数字。
            int colon = candidate.indexOf(':');
            String host = candidate.substring(0, colon);
            String port = candidate.substring(colon + 1);
            if (host.isEmpty() || port.isEmpty() || !isAsciiDigits(port)) {
                return null;
            }
            candidate = host;
        }
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH) {
            return null;
        }
        // 连 IP 的基本分隔符都没有的值（例如 unknown、localhost）不作为限流维度使用。
        if (candidate.indexOf('.') < 0 && candidate.indexOf(':') < 0) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (!isAllowedIpCharacter(candidate.charAt(i))) {
                return null;
            }
        }
        return candidate;
    }

    /**
     * 允许字母、数字、点、冒号、短横线和百分号；其余字符（包括逗号、空格、换行）一律拒绝。
     */
    private static boolean isAllowedIpCharacter(char character) {
        return isAsciiLetterOrDigit(character)
                || character == '.'
                || character == ':'
                || character == '-'
                || character == '%';
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
    }

    private static boolean isAsciiDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static int countColons(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ':') {
                count++;
            }
        }
        return count;
    }
}
