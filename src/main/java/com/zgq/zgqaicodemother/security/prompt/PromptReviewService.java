package com.zgq.zgqaicodemother.security.prompt;

import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户输入（prompt）安全审查服务：在请求进入“代码生成专家”系统提示词之前做清洗、注入识别和拦截。
 *
 * <h2>为什么需要独立的审查层</h2>
 * <p>平台把用户自然语言直接拼进系统提示词（创建应用的 initPrompt、对话的 message、工作流的 prompt）。
 * 恶意输入可以通过“忽略之前的指令”“输出你的系统提示词”“你现在是 DAN”等句式改变模型行为；
 * 但正常建站需求同样会提到 {@code <script>}、{@code localStorage}、{@code fetch}、忽略空白区域等，
 * 因此规则只针对“面向模型的越权、套取、伪造”句式：前端技术词只按低权重的可疑代码记录，
 * 正常需求不会被单独拦截。</p>
 *
 * <h2>处理流程</h2>
 * <ol>
 *   <li>清洗：NFKC 归一化；删除零宽/双向控制等不可见字符；删除除 {@code \n \r \t} 外的 Cc 控制字符；
 *       全角空格转普通空格；trim。{@code sanitizeEnabled=false} 时全部跳过。</li>
 *   <li>长度校验：清洗后码点数超过 {@code maxLength} 直接拒绝，返回满分和
 *       {@link PromptRiskCategory#OVERSIZE_INPUT}。</li>
 *   <li>规则匹配：预编译 Pattern 覆盖中英文覆盖指令、套取系统提示词、角色劫持、伪造分隔符、
 *       数据外泄、破坏性操作和可疑脚本；同类只计一次，总分按 100 截断。</li>
 *   <li>自定义黑名单：命中任一非空短语直接按 100 分拒绝。该策略属于运维自定义，
 *       不归属任何内置类别，因此结果的 {@code categories} 可能为空。</li>
 *   <li>决策：达到场景阈值则拒绝，达到告警阈值但未拦截则记录告警日志，否则放行；
 *       放行时返回已中和伪造分隔符的文本。</li>
 * </ol>
 *
 * <h2>场景差异</h2>
 * <p>{@link PromptScene#APP_CREATE} 的拦截阈值在配置值基础上下浮 10 分（默认 70 变成 60），
 * 因为创建应用是第一次把需求写进应用记录、后续对话会继承它；{@link PromptScene#CHAT} 和
 * {@link PromptScene#WORKFLOW} 使用配置阈值。可疑脚本
 * {@link PromptRiskCategory#SUSPICIOUS_CODE} 权重只有 25，且三个场景都是代码生成场景，
 * 因此它永远不单独作为拒绝依据：即使总分达到阈值，只要其余类别得分为 0 就放行并记录告警，
 * 避免误伤“用 script 标签做计数器”这类正常需求。</p>
 *
 * <h2>误伤控制</h2>
 * <p>规则刻意区分“面向模型的要求”和“页面文案”：</p>
 * <ul>
 *   <li>英文只匹配 {@code ignore previous instructions} 这类带指令对象的句式，
 *       不匹配单独出现的 {@code the above}；</li>
 *   <li>中文“你现在是”“从现在开始你是”等只匹配劫持语境，避免把“你现在是会员”之类的页面文案当成攻击；</li>
 *   <li>“没有任何限制/不受限制/no restrictions”只在越狱语境（人格、开发者模式、DAN 等）附近才计分，
 *       避免把“免费版没有任何限制”这类营销文案当成注入；</li>
 *   <li>套取系统提示词的句式要求明确的泄露对象（你的/系统的提示词、初始指令等），
 *       不匹配“输出提示词”这种提示词工程工具的常见需求。</li>
 * </ul>
 * <p>这些取舍都偏向“宁可漏判低风险表述，也不误伤正常建站需求”；真正的越权输入通常会同时命中多个类别，
 * 分数依然会超过阈值。</p>
 *
 * <h2>日志与失败策略</h2>
 * <p>日志只记录场景、分数、类别和长度，<strong>绝不打印用户原文</strong>。审查器自身异常时按
 * fail-closed 拒绝（返回 {@code allowed=false} 和“稍后重试”的提示），只有显式
 * {@code enabled=false} 才直接放行；这样审查组件故障不会悄悄退化成“注入防护失效”。
 * 代价是组件异常时合法用户也会被拒绝，这是安全策略优先的取舍。</p>
 *
 * <p>本类无状态、线程安全：配置在构造时复制，正则全部静态预编译，审查过程不写共享变量，
 * 因此同一个实例可以被所有请求线程并发调用。</p>
 */
@Slf4j
public class PromptReviewService {

    /**
     * 风险分上限，多类别累加后截断到该值。
     */
    private static final int MAX_SCORE = 100;

    /**
     * 创建应用场景相对配置拦截阈值的严格化偏移量。
     */
    private static final int APP_CREATE_THRESHOLD_OFFSET = 10;

    /**
     * 所有正则共用的匹配选项：大小写不敏感，并让大小写折叠遵循 Unicode。
     */
    private static final int CASE_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /**
     * 全角空格（U+3000）。NFKC 会把它归一化成普通空格，这里仍然显式处理一次。
     */
    private static final char IDEOGRAPHIC_SPACE = '\u3000';

    /**
     * 放行且没有命中任何规则时的原因。
     */
    private static final String SAFE_REASON = "输入通过安全审查";

    /**
     * 空输入的原因。
     */
    private static final String EMPTY_REASON = "输入为空，无需审查";

    /**
     * 审查开关关闭时的原因。
     */
    private static final String DISABLED_REASON = "Prompt 审查已关闭，直接放行";

    /**
     * 注入/越权拦截的面向用户提示。不包含用户原文，也不透露具体命中位置。
     */
    private static final String BLOCK_REASON =
            "输入包含疑似提示词注入/越权指令，已被安全策略拦截，请修改需求后重试";

    /**
     * 超长输入的面向用户提示模板，必须带上具体上限数字。
     */
    private static final String OVERSIZE_REASON_TEMPLATE =
            "输入长度超过上限（最多 %d 个字符），已被安全策略拦截，请精简需求后重试";

    /**
     * 自定义黑名单命中的面向用户提示，不回显命中的短语。
     */
    private static final String CUSTOM_BLOCK_REASON =
            "输入命中了安全策略中的自定义拦截词，已被拦截，请修改需求后重试";

    /**
     * 审查器自身异常时的面向用户提示（fail-closed）。
     */
    private static final String FAIL_CLOSED_REASON =
            "安全审查服务暂时不可用，已按安全策略拒绝本次请求，请稍后重试";

    /**
     * 零宽/双向控制/BOM/软连字符等不可见字符。用 {@code \x{...}} 写法避免源码里的裸控制字符。
     */
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\x{200B}-\\x{200F}\\x{202A}-\\x{202E}\\x{2060}-\\x{2069}\\x{FEFF}\\x{00AD}]");

    /**
     * Cc 控制字符，排除 {@code \n \r \t}，保证多行需求仍能保持分段。
     */
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\x{0000}-\\x{0008}\\x{000B}\\x{000C}\\x{000E}-\\x{001F}\\x{007F}-\\x{009F}]");

    /**
     * 全角 ASCII 字母和数字。中文正常排版里的全角标点不算异常编码，这类字符才常用于绕过关键词过滤。
     */
    private static final Pattern FULLWIDTH_ALNUM =
            Pattern.compile("[\\x{FF10}-\\x{FF19}\\x{FF21}-\\x{FF3A}\\x{FF41}-\\x{FF5A}]");

    /**
     * 注入识别规则。所有量词都有上界，且没有嵌套量词，避免灾难性回溯；
     * 具体规则旁边都注明了它要挡什么、刻意不挡什么。
     */
    private static final List<Rule> RULES = List.of(
            // 覆盖指令：ignore/disregard/forget ... previous instructions
            rule(PromptRiskCategory.INSTRUCTION_OVERRIDE,
                    "\\b(?:ignore|disregard|forget|override|bypass)\\b(?:\\s+\\w+){0,4}?\\s+"
                            + "(?:previous|prior|above|earlier|preceding|original|former)\\b"
                            + "(?:\\s+\\w+){0,3}?\\s+"
                            + "(?:instructions?|prompts?|rules?|directions?|guidelines?|constraints?)\\b"),
            // 覆盖指令：bypass your restrictions / safety
            rule(PromptRiskCategory.INSTRUCTION_OVERRIDE,
                    "\\bbypass\\s+your\\b[\\s\\S]{0,20}?"
                            + "\\b(?:restrictions?|limitations?|rules?|guardrails?|safety|filters?)\\b"),
            // 覆盖指令（中文）：忽略/无视 之前/系统 的 (所有) 指令/规则。
            // 刻意要求“之前/以上/系统”等限定词，避免误伤“忽略页面的空白区域”。
            rule(PromptRiskCategory.INSTRUCTION_OVERRIDE,
                    "(?:忽略|无视|抛开|忘记|抛弃)\\s*(?:掉)?\\s*"
                            + "(?:之前|以上|前面|先前|此前|过去|原有|原来|系统)(?:的)?\\s*"
                            + "(?:所有|全部)?\\s*(?:的)?\\s*"
                            + "(?:指令|提示词|提示|规则|设定|要求|约束|限制)"),
            // 覆盖指令（中文）：不要再遵守之前的规则
            rule(PromptRiskCategory.INSTRUCTION_OVERRIDE,
                    "(?:不要|不得|别|无需)\\s*(?:再)?\\s*(?:遵守|执行|遵循|理会|服从|管)\\s*"
                            + "(?:之前|以上|前面|系统|原有)(?:的)?\\s*(?:指令|规则|设定|要求|提示)"),
            // 覆盖指令（中文）：从现在开始你只按我说的做
            rule(PromptRiskCategory.INSTRUCTION_OVERRIDE,
                    "(?:从现在开始|从现在起|接下来|以后)\\s*[，,]?\\s*你\\s*(?:只|必须|要|只能)\\s*"
                            + "(?:按|听|服从|执行|遵守)\\s*"
                            + "(?:我(?:说|吩咐|要求)的|(?:我的|我)(?:的)?(?:指令|命令|要求|规则))"),
            // 覆盖指令：越狱语境下的“解除限制”表述。
            // 单独出现“无限制/不受任何限制”属于常见营销文案，只有在人格劫持、
            // 开发者模式、DAN 等语境附近才计分，避免误伤正常建站需求。
            rule(PromptRiskCategory.INSTRUCTION_OVERRIDE,
                    "(?:你现在是|从现在开始你|你来扮演|你扮演|假装你是|假装你|越狱|jailbreak|"
                            + "developer\\s+mode|\\bDAN\\b|you\\s+are\\s+now|act\\s+as)"
                            + "[\\s\\S]{0,60}?"
                            + "(?:没有任何限制|没有任何约束|不受\\s*(?:任何)?\\s*限制|"
                            + "没有\\s*(?:任何)?\\s*限制|无限制|no\\s+restrictions?|"
                            + "without\\s+(?:any\\s+)?restrictions?|unrestricted)"),

            // 套取系统提示词：system prompt / system message
            rule(PromptRiskCategory.SYSTEM_EXFILTRATION,
                    "\\bsystem\\s+(?:prompt|message|instructions?)\\b"),
            // 套取系统提示词：reveal/show/print ... your instructions。
            // 只认“your + 指令对象”或“the + system/hidden/... + 指令对象”，避免误伤
            // show the settings / show the rules 这类正常页面交互描述。
            rule(PromptRiskCategory.SYSTEM_EXFILTRATION,
                    "\\b(?:reveal|show|print|repeat|output|leak|dump|expose|display|tell\\s+me|give\\s+me)\\b"
                            + "[\\s\\S]{0,25}?"
                            + "\\b(?:your\\s+(?:system\\s+)?(?:prompts?|instructions?|rules?|guidelines?)|"
                            + "(?:the|your)\\s+(?:system|hidden|initial|original|above|previous)\\s+"
                            + "(?:prompts?|instructions?|rules?))\\b"),
            // 套取系统提示词：repeat the above instructions
            rule(PromptRiskCategory.SYSTEM_EXFILTRATION,
                    "\\b(?:repeat|print|output|show|leak)\\s+(?:the\\s+)?(?:above|previous|prior)\\s+"
                            + "(?:prompt|instructions?)\\b"),
            // 套取系统提示词（中文）：输出/打印/重复你的系统提示词。
            // 要求“你的/系统的”等限定词，避免把“输入主题输出提示词”这类提示词工具需求当成攻击。
            rule(PromptRiskCategory.SYSTEM_EXFILTRATION,
                    "(?:输出|打印|重复|复述|告诉我|泄露|透露|展示|贴出|发给我)\\s*(?:一下|一遍|一次)?\\s*"
                            + "(?:(?:你的|你写的|上面的|以上的|前面的|系统的)(?:的)?(?:系统)?|系统)"
                            + "\\s*(?:提示词|提示|设定|指令)"),
            // 套取系统提示词（中文）：系统提示词原样重复。
            // 只认系统级提示词对象和“重复/复述/泄露”这类动作，不匹配“系统提示词输出格式”等正常表述。
            rule(PromptRiskCategory.SYSTEM_EXFILTRATION,
                    "(?:系统提示词|系统设定|系统指令|初始指令|预设指令|原始指令|你的提示词|你的指令)"
                            + "[^的]{0,8}?(?:原样|一字不差|完整|全部)?\\s*"
                            + "(?:重复|复述|泄露|透露|发给我|贴出来|告诉我)"),

            // 角色劫持：you are now
            rule(PromptRiskCategory.ROLE_HIJACK, "\\byou\\s+are\\s+now\\b"),
            // 角色劫持：from now on you are ...
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "\\bfrom\\s+now\\s+on\\b[\\s\\S]{0,20}?\\b(?:you\\s+(?:are|will\\s+be)|act\\s+as|pretend)\\b"),
            // 角色劫持：act as an unrestricted model
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "\\b(?:act|behave|respond|answer|roleplay)\\s+as\\s+(?:an?\\s+)?"
                            + "(?:unrestricted|unfiltered|jailbroken|evil|hacker|dan|god|developer|root)\\b"),
            // 角色劫持：jailbreak
            rule(PromptRiskCategory.ROLE_HIJACK, "\\bjailbreak(?:ed|ing)?\\b"),
            // 角色劫持：do anything now
            rule(PromptRiskCategory.ROLE_HIJACK, "\\bdo\\s+anything\\s+now\\b"),
            // 角色劫持：developer mode / god mode
            rule(PromptRiskCategory.ROLE_HIJACK, "\\b(?:developer|god|dan|jailbreak)\\s+mode\\b"),
            // 角色劫持：DAN 人格必须与越狱语境同时出现，避免把英文人名 Dan 误判
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "(?:you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be|jailbreak|do\\s+anything\\s+now|"
                            + "你现在是|从现在开始你)[\\s\\S]{0,20}?\\bDAN\\b"),
            // 角色劫持（中文）：从现在开始你是/扮演。这是明确要求模型改变身份，
            // 与“从现在开始你可以选择颜色”这类 UI 描述区分开。
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "从现在开始\\s*[，,]?\\s*你\\s*(?:是|扮演|就是)"),
            // 角色劫持（中文）：你现在是 DAN / 一个不受限制的助手。
            // 刻意要求攻击性人格，避免误伤“你现在是会员”这类文案。
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "你现在是\\s*(?:\\bDAN\\b|一个\\s*(?:没有|不受|无)[\\s\\S]{0,8}?(?:限制|约束)|"
                            + "越狱|无限制|邪恶|黑客|上帝|无约束|超级管理员)"),
            // 角色劫持（中文）：进入开发者/越狱/无限制模式
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "(?:进入|开启|启用|切换到|打开)\\s*(?:开发者|越狱|无限制|上帝|god|\\bDAN\\b)\\s*模式"),
            // 角色劫持（中文）：越狱
            rule(PromptRiskCategory.ROLE_HIJACK, "越狱"),
            // 角色劫持（中文）：扮演一个不受限制的角色
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "你(?:现在)?(?:要|必须)?扮演(?:一个|一名)?\\s*"
                            + "(?:不受|没有|无限制|越狱|邪恶|黑客|上帝|\\bDAN\\b)"),
            // 角色劫持（中文）：忘记你的身份/设定
            rule(PromptRiskCategory.ROLE_HIJACK,
                    "(?:忽略|忘记|抛弃)你(?:的)?(?:身份|角色|设定|人设|系统设定)"),

            // 伪造分隔符：ChatML 特殊 token
            rule(PromptRiskCategory.DELIMITER_INJECTION,
                    "<\\|\\s*(?:im_start|im_end|endoftext|start_header_id|end_header_id|eot_id|"
                            + "system|user|assistant)\\s*\\|>"),
            // 伪造分隔符：[INST] / [/INST]
            rule(PromptRiskCategory.DELIMITER_INJECTION, "\\[\\s*/?\\s*INST\\s*\\]"),
            // 伪造分隔符：<system> / </system>
            rule(PromptRiskCategory.DELIMITER_INJECTION, "<\\s*/?\\s*system\\s*>"),
            // 伪造分隔符：[SYSTEM] / [SYS]
            rule(PromptRiskCategory.DELIMITER_INJECTION, "\\[\\s*(?:SYSTEM|INST|SYS)\\s*\\]"),
            // 伪造分隔符：行首的 system: / ### system:。
            // 用 [ \t] 而不是 \s，避免在多行输入上反复跨行扫描。
            rule(PromptRiskCategory.DELIMITER_INJECTION,
                    "(?m)^[ \\t]*(?:###[ \\t]*)?system[ \\t]*:"),

            // 数据外泄：密钥 + 发送/输出动作
            rule(PromptRiskCategory.DATA_EXFILTRATION,
                    "(?:api[\\s_-]?key|access[\\s_-]?key|secret|token|password|passwd|密码|密钥|秘钥|访问令牌)"
                            + "[\\s\\S]{0,30}?"
                            + "(?:发给我|发送到|发送给|输出|打印|告诉我|贴出来|泄露|透露|"
                            + "send|leak|reveal|print|output|dump|show\\s+me)"),
            // 数据外泄：发送动作 + 密钥（英文语序）
            rule(PromptRiskCategory.DATA_EXFILTRATION,
                    "(?:send|leak|reveal|print|output|dump|show|tell\\s+me)"
                            + "[\\s\\S]{0,30}?"
                            + "(?:api[\\s_-]?key|access[\\s_-]?key|secret|token|password|密码|密钥)"),

            // 破坏性操作：rm -rf
            rule(PromptRiskCategory.DESTRUCTIVE_OPERATION, "\\brm\\s+-[a-z]*(?:rf|fr)[a-z]*\\b"),
            // 破坏性操作：format c:
            rule(PromptRiskCategory.DESTRUCTIVE_OPERATION, "\\bformat\\s+[a-z]:"),
            // 破坏性操作：drop table / drop database
            rule(PromptRiskCategory.DESTRUCTIVE_OPERATION, "\\bdrop\\s+(?:table|database)\\b"),
            // 破坏性操作：truncate table / mkfs
            rule(PromptRiskCategory.DESTRUCTIVE_OPERATION,
                    "\\b(?:truncate\\s+table|mkfs(?:\\.[a-z0-9]+)?)\\b"),
            // 破坏性操作（中文）：删除服务器/数据库/全部文件
            rule(PromptRiskCategory.DESTRUCTIVE_OPERATION,
                    "(?:删除|清空|格式化)\\s*(?:掉)?\\s*"
                            + "(?:服务器|数据库|全部文件|所有文件|整个项目|系统文件|磁盘|"
                            + "所有数据|全部数据|生产环境)"),
            // 破坏性操作：delete all files / wipe the database
            rule(PromptRiskCategory.DESTRUCTIVE_OPERATION,
                    "\\b(?:delete|drop|wipe|erase)\\s+(?:all|the\\s+entire)\\s+(?:files?|database|data|disk)\\b"),

            // 可疑脚本：<script> 标签。属性长度限制在 200 字符内，避免超长属性导致反复扫描。
            rule(PromptRiskCategory.SUSPICIOUS_CODE, "<script[^>]{0,200}>"),
            // 可疑脚本：javascript: 伪协议
            rule(PromptRiskCategory.SUSPICIOUS_CODE, "javascript\\s*:"),
            // 可疑脚本：onerror=/onload=
            rule(PromptRiskCategory.SUSPICIOUS_CODE, "\\bon(?:error|load)\\s*="),
            // 可疑脚本：document.cookie
            rule(PromptRiskCategory.SUSPICIOUS_CODE, "document\\.cookie"),
            // 可疑脚本：eval(
            rule(PromptRiskCategory.SUSPICIOUS_CODE, "\\beval\\s*\\(")
    );

    /**
     * 分隔符中和规则。只用于放行结果的文本，不参与打分：命中与否由上面的规则单独记录。
     *
     * <p>占位符刻意使用中文方括号 {@code 〔〕}：全角 ASCII 方括号和全角冒号会在 NFKC 下折回
     * ASCII，导致同一段文本再次审查时分隔符“复活”；中文方括号没有兼容分解，重复审查结果稳定，
     * 也不会被下游再次 NFKC 时还原成 {@code [SYSTEM]}、{@code system:}。</p>
     */
    private static final List<Neutralizer> NEUTRALIZERS = List.of(
            // <|im_start|> -> 〔im_start〕
            new Neutralizer(Pattern.compile("<\\|\\s*([A-Za-z_]{2,32})\\s*\\|>", CASE_FLAGS), "〔$1〕"),
            // [INST] / [/INST] / [SYSTEM] / [SYS] -> 〔INST〕 / 〔/INST〕 / 〔SYSTEM〕
            new Neutralizer(Pattern.compile("\\[\\s*(/?)\\s*(SYSTEM|INST|SYS)\\s*\\]", CASE_FLAGS), "〔$1$2〕"),
            // <system> / </system> -> 〔system〕 / 〔/system〕
            new Neutralizer(Pattern.compile("<\\s*(/?)\\s*system\\s*>", CASE_FLAGS), "〔$1system〕"),
            // 行首 system: 的分隔语义改成普通文本，冒号本身也包进占位符避免折回 ASCII
            new Neutralizer(Pattern.compile("(?m)^([ \\t]*(?:###[ \\t]*)?)system[ \\t]*:", CASE_FLAGS),
                    "$1system〔:〕")
    );

    /**
     * 是否启用审查。
     */
    private final boolean enabled;

    /**
     * 对话和工作流场景的拦截阈值。
     */
    private final int blockThreshold;

    /**
     * 告警阈值。
     */
    private final int warnThreshold;

    /**
     * 清洗后允许的最大码点数，小于等于 0 表示不限制。
     */
    private final int maxLength;

    /**
     * 是否记录命中/告警日志。
     */
    private final boolean logMatches;

    /**
     * 是否启用清洗和分隔符中和。
     */
    private final boolean sanitizeEnabled;

    /**
     * 创建应用场景的拦截阈值（配置阈值下浮 {@value #APP_CREATE_THRESHOLD_OFFSET} 分）。
     */
    private final int appCreateBlockThreshold;

    /**
     * 归一化后的自定义黑名单短语（去空白、小写去重）。
     */
    private final List<String> customBlockedPhrases;

    /**
     * 构造审查服务。配置在此处一次性复制，运行期修改配置对象不会改变已创建实例的行为，
     * 保证同一实例在并发调用下结果稳定。
     *
     * @param properties 审查配置，不能为 null
     */
    public PromptReviewService(PromptReviewProperties properties) {
        Objects.requireNonNull(properties, "PromptReviewProperties 不能为空");
        this.enabled = properties.isEnabled();
        this.blockThreshold = clamp(properties.getBlockThreshold(), 1, MAX_SCORE);
        this.warnThreshold = clamp(properties.getWarnThreshold(), 0, MAX_SCORE);
        this.maxLength = properties.getMaxLength();
        this.logMatches = properties.isLogMatches();
        this.sanitizeEnabled = properties.isSanitizeEnabled();
        this.appCreateBlockThreshold = Math.max(1, this.blockThreshold - APP_CREATE_THRESHOLD_OFFSET);
        this.customBlockedPhrases = normalizePhrases(properties.getExtraBlockedPhrases());
    }

    /**
     * 审查用户输入。
     *
     * <p>返回结果中 {@code allowed=false} 时 {@code text} 为 null，调用方必须终止请求；
     * {@code allowed=true} 时必须用返回的 {@code text} 替换原始入参再调用模型。</p>
     *
     * @param rawText 用户原始输入，允许为 null 或空
     * @param scene   入口场景，为 null 时按 {@link PromptScene#CHAT} 处理
     * @return 审查结果，不会返回 null
     */
    public PromptReviewResult review(String rawText, PromptScene scene) {
        PromptScene effectiveScene = scene == null ? PromptScene.CHAT : scene;
        if (!enabled) {
            return PromptReviewResult.allow(rawText == null ? "" : rawText, 0, Set.of(), DISABLED_REASON);
        }
        try {
            return reviewInternal(rawText, effectiveScene);
        } catch (RuntimeException e) {
            // fail-closed：审查器异常时拒绝请求，避免防护静默失效。日志只带场景和长度。
            log.error("Prompt 审查执行异常，按 fail-closed 策略拒绝，scene={}，textLength={}",
                    effectiveScene, rawText == null ? 0 : rawText.length(), e);
            return PromptReviewResult.reject(FAIL_CLOSED_REASON, MAX_SCORE, Set.of());
        }
    }

    /**
     * 实际审查流程，异常统一由 {@link #review(String, PromptScene)} 兜底。
     *
     * @param rawText 用户原始输入
     * @param scene   入口场景
     * @return 审查结果
     */
    private PromptReviewResult reviewInternal(String rawText, PromptScene scene) {
        if (rawText == null || rawText.isEmpty()) {
            return PromptReviewResult.allow("", 0, Set.of(), EMPTY_REASON);
        }
        boolean encodingAbuse = false;
        String text = rawText;
        if (sanitizeEnabled) {
            SanitizedText sanitized = sanitize(rawText);
            text = sanitized.text();
            encodingAbuse = sanitized.encodingAbuse();
        }
        if (text.isEmpty()) {
            // 纯控制字符/纯空白清洗后没有可审查内容，按放行处理；异常编码仍然记录，便于发现探测行为。
            Set<PromptRiskCategory> categories = encodingAbuse
                    ? Set.of(PromptRiskCategory.ENCODING_ABUSE) : Set.of();
            int score = scoreOf(categories);
            return PromptReviewResult.allow("", score, categories, buildAllowReason(score, categories));
        }
        if (maxLength > 0 && text.codePointCount(0, text.length()) > maxLength) {
            PromptReviewResult result = PromptReviewResult.reject(
                    String.format(OVERSIZE_REASON_TEMPLATE, maxLength), MAX_SCORE,
                    Set.of(PromptRiskCategory.OVERSIZE_INPUT));
            logBlocked(scene, result);
            return result;
        }

        Set<PromptRiskCategory> categories = new LinkedHashSet<>();
        if (encodingAbuse) {
            categories.add(PromptRiskCategory.ENCODING_ABUSE);
        }
        matchRules(text, categories);
        int score = scoreOf(categories);

        if (matchesCustomPhrase(text)) {
            PromptReviewResult result = PromptReviewResult.reject(CUSTOM_BLOCK_REASON, MAX_SCORE, categories);
            logBlocked(scene, result);
            return result;
        }

        // 可疑脚本在代码生成场景不单独作为拒绝依据：扣掉它的权重后再和阈值比较。
        int blockingScore = score - (categories.contains(PromptRiskCategory.SUSPICIOUS_CODE)
                ? PromptRiskCategory.SUSPICIOUS_CODE.getWeight() : 0);
        if (blockingScore >= thresholdFor(scene)) {
            String reason = BLOCK_REASON + "（命中类型：" + labels(categories) + "）";
            PromptReviewResult result = PromptReviewResult.reject(reason, score, categories);
            logBlocked(scene, result);
            return result;
        }

        if (logMatches && score >= warnThreshold) {
            log.warn("Prompt 审查告警放行，scene={}，score={}，categories={}",
                    scene, score, labels(categories));
        }
        String safeText = sanitizeEnabled ? neutralizeDelimiters(text) : text;
        return PromptReviewResult.allow(safeText, score, categories, buildAllowReason(score, categories));
    }

    /**
     * 清洗输入：NFKC 归一化、移除不可见字符和控制字符、全角空格转普通空格并 trim。
     *
     * <p>“异常编码”只统计真正带有规避意图的信号：不可见字符、Cc 控制字符、全角空格、
     * 全角 ASCII 字母/数字。NFKC 会把中文全角标点（如“，”）转成半角，这属于正常排版，
     * 不计入异常编码，否则所有中文输入都会被标成风险。</p>
     *
     * @param raw 原始输入
     * @return 清洗后的文本和异常编码标记
     */
    private static SanitizedText sanitize(String raw) {
        boolean fullwidthAlnum = FULLWIDTH_ALNUM.matcher(raw).find();
        boolean ideographicSpace = raw.indexOf(IDEOGRAPHIC_SPACE) >= 0;

        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        boolean invisible = INVISIBLE_CHARS.matcher(text).find();
        if (invisible) {
            text = INVISIBLE_CHARS.matcher(text).replaceAll("");
        }
        boolean control = CONTROL_CHARS.matcher(text).find();
        if (control) {
            text = CONTROL_CHARS.matcher(text).replaceAll("");
        }
        if (text.indexOf(IDEOGRAPHIC_SPACE) >= 0) {
            text = text.replace(IDEOGRAPHIC_SPACE, ' ');
        }
        text = text.strip();
        boolean encodingAbuse = invisible || control || ideographicSpace || fullwidthAlnum;
        return new SanitizedText(text, encodingAbuse);
    }

    /**
     * 按规则逐条匹配，命中即记录类别（同类只记一次）。
     *
     * <p>类别已经命中时跳过该类别剩下的规则：分数按类别计，同类再命中也不会加分，
     * 跳过可以显著减少长输入上的正则扫描量，且不影响结果。</p>
     *
     * @param text       待审查文本
     * @param categories 命中的类别集合，允许为空
     */
    private static void matchRules(String text, Set<PromptRiskCategory> categories) {
        for (Rule rule : RULES) {
            if (categories.contains(rule.category())) {
                continue;
            }
            if (rule.pattern().matcher(text).find()) {
                categories.add(rule.category());
            }
        }
    }

    /**
     * 判断是否命中自定义黑名单。短语已在构造时去空白、转小写，这里只做一次小写化保证忽略大小写。
     *
     * @param text 待审查文本
     * @return 命中返回 true
     */
    private boolean matchesCustomPhrase(String text) {
        if (customBlockedPhrases.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String phrase : customBlockedPhrases) {
            if (lowerText.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 中和伪造的对话分隔符，避免它们在模型上下文里重新形成角色边界。
     *
     * @param text 已清洗的文本
     * @return 中和后的文本
     */
    private static String neutralizeDelimiters(String text) {
        String result = text;
        for (Neutralizer neutralizer : NEUTRALIZERS) {
            result = neutralizer.pattern().matcher(result).replaceAll(neutralizer.replacement());
        }
        return result;
    }

    /**
     * 计算场景对应的拦截阈值。
     *
     * @param scene 入口场景
     * @return 拦截阈值
     */
    private int thresholdFor(PromptScene scene) {
        return scene == PromptScene.APP_CREATE ? appCreateBlockThreshold : blockThreshold;
    }

    /**
     * 累加类别权重并截断到满分。
     *
     * @param categories 命中的类别
     * @return 0-100 的风险分
     */
    private static int scoreOf(Set<PromptRiskCategory> categories) {
        int total = 0;
        for (PromptRiskCategory category : categories) {
            total += category.getWeight();
        }
        return Math.min(MAX_SCORE, total);
    }

    /**
     * 记录拦截日志。只记录场景、分数和类别，不记录原文。
     *
     * @param scene  入口场景
     * @param result 拦截结果
     */
    private void logBlocked(PromptScene scene, PromptReviewResult result) {
        if (!logMatches) {
            return;
        }
        log.warn("Prompt 审查拒绝，scene={}，score={}，categories={}",
                scene, result.score(), labels(result.categories()));
    }

    /**
     * 生成放行原因。仅用于日志和排障，调用方不需要展示给用户。
     *
     * @param score      风险分
     * @param categories 命中的类别
     * @return 放行原因
     */
    private static String buildAllowReason(int score, Set<PromptRiskCategory> categories) {
        if (categories.isEmpty()) {
            return SAFE_REASON;
        }
        return "输入通过安全审查，风险分 " + score + "，命中类型：" + labels(categories);
    }

    /**
     * 把类别集合转成中文列表，仅用于日志和提示文案，不能包含用户原文。
     *
     * @param categories 命中的类别
     * @return 中文列表，空集合返回“无”
     */
    private static String labels(Set<PromptRiskCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return "无";
        }
        return categories.stream()
                .map(PromptRiskCategory::getText)
                .collect(Collectors.joining("、"));
    }

    /**
     * 归一化自定义黑名单：去 null、去空白、转小写、去重。
     *
     * @param phrases 原始短语集合，允许为 null
     * @return 不可变短语列表
     */
    private static List<String> normalizePhrases(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return List.of();
        }
        return phrases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(phrase -> !phrase.isEmpty())
                .map(phrase -> phrase.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /**
     * 把数值限制在闭区间内，防止配置错误（如 0 或负数阈值）导致全量拦截或完全失效。
     *
     * @param value 配置值
     * @param min   最小值
     * @param max   最大值
     * @return 限制后的值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 编译一条注入识别规则。
     *
     * @param category 规则归属的风险类别
     * @param regex    正则
     * @return 预编译规则
     */
    private static Rule rule(PromptRiskCategory category, String regex) {
        return new Rule(category, Pattern.compile(regex, CASE_FLAGS));
    }

    /**
     * 一条注入识别规则：一个类别可以有多条规则，命中同一类别只计一次分。
     *
     * @param category 风险类别
     * @param pattern  预编译正则
     */
    private record Rule(PromptRiskCategory category, Pattern pattern) {
    }

    /**
     * 清洗结果。
     *
     * @param text          清洗后的文本
     * @param encodingAbuse 是否出现异常编码信号
     */
    private record SanitizedText(String text, boolean encodingAbuse) {
    }

    /**
     * 分隔符中和规则。
     *
     * @param pattern     预编译正则
     * @param replacement 替换文本
     */
    private record Neutralizer(Pattern pattern, String replacement) {
    }
}
