package com.zgq.zgqaicodemother.security.prompt;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

/**
 * Prompt 审查配置，绑定 {@code app.security.prompt-review} 前缀。
 *
 * <p>所有字段都有安全默认值，因此不写任何 YAML 也能正常启动：开启审查、70 分拦截、
 * 40 分告警、单条输入最多 10000 个码点。生产环境按需通过环境变量或
 * application-local.yml 调整，例如：</p>
 *
 * <pre>{@code
 * app:
 *   security:
 *     prompt-review:
 *       enabled: true
 *       block-threshold: 70
 *       warn-threshold: 40
 *       max-length: 10000
 *       log-matches: true
 *       sanitize-enabled: true
 *       extra-blocked-phrases:
 *         - 公司内部机密
 * }</pre>
 *
 * <p>这类配置属于安全策略，修改后需要重启应用（审查服务在创建时复制配置），
 * 不要在运行期直接修改配置对象来“临时放宽”。</p>
 */
@Data
public class PromptReviewProperties {

    /**
     * 是否启用 Prompt 审查。关闭后所有输入直接放行，既不拦截也不清洗，
     * 仅建议排障时临时关闭，生产环境应保持开启。
     */
    private boolean enabled = true;

    /**
     * 拦截阈值：风险分达到该值即拒绝。默认 70，调低更严格。
     * 创建应用场景会在此基础上再下浮 10 分。
     */
    private int blockThreshold = 70;

    /**
     * 告警阈值：风险分达到该值但未拦截时，记录一条只包含场景、分数和类别的告警日志。
     * 默认 40，用于观察规则是否过严，不应高于 blockThreshold。
     */
    private int warnThreshold = 40;

    /**
     * 清洗后输入允许的最大码点数（code point），超过直接拒绝。默认 10000，与应用初始化
     * prompt 的业务上限一致；小于等于 0 表示不限制长度（不推荐）。
     */
    private int maxLength = 10000;

    /**
     * 是否记录命中日志。日志只记录场景、分数、类别和长度，绝不记录用户原文；
     * 关闭后不影响拦截行为，只关闭命中/告警日志。
     */
    private boolean logMatches = true;

    /**
     * 是否启用清洗与分隔符中和。关闭后零宽字符、控制字符和伪造的角色分隔符会原样进入
     * 模型上下文，仅建议排障时临时关闭。
     */
    private boolean sanitizeEnabled = true;

    /**
     * 运维补充的自定义黑名单短语，忽略大小写，任意一条非空短语出现在输入中即按满分 100 拒绝。
     * 适合拦截业务侧已知的敏感词或内部代号；短语不会回显在用户提示里。
     */
    private Set<String> extraBlockedPhrases = new HashSet<>();
}
