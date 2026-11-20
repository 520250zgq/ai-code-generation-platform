package com.zgq.zgqaicodemother.security.prompt;

import lombok.Getter;

/**
 * Prompt 风险类别。
 *
 * <p>每个类别带一个默认权重，审查器按“命中的类别分数累加、同类只计一次、满分 100 截断”计算
 * 风险分。权重代表默认的严重程度，最终是否拦截由
 * {@link PromptReviewProperties#getBlockThreshold()} 决定，而不是类别本身：
 * 单独命中 {@link #SUSPICIOUS_CODE} 只有 25 分，正常情况下不会导致拒绝。</p>
 *
 * <p>调整权重会直接影响现网拦截率，新增类别或改权重时请同步补充
 * {@code PromptReviewServiceTest} 的正例和反例，避免误伤正常建站需求。</p>
 */
@Getter
public enum PromptRiskCategory {

    /**
     * 覆盖指令：要求模型忽略系统提示词或之前的所有指令。
     */
    INSTRUCTION_OVERRIDE("覆盖指令", 70),

    /**
     * 套取系统提示词：要求模型输出、重复或泄露它的系统提示词/初始指令。
     */
    SYSTEM_EXFILTRATION("套取系统提示词", 75),

    /**
     * 角色劫持/越狱：让模型扮演无限制角色、进入开发者模式或摆脱规则约束。
     */
    ROLE_HIJACK("角色劫持", 65),

    /**
     * 伪造对话分隔符：伪造 system/assistant 角色标记或 ChatML 特殊 token。
     */
    DELIMITER_INJECTION("伪造对话分隔符", 45),

    /**
     * 诱导泄露密钥或隐私数据：要求把 API Key、密码、Token 等发送出去。
     */
    DATA_EXFILTRATION("诱导泄露敏感数据", 55),

    /**
     * 诱导删除、格式化、清空等破坏性操作。
     */
    DESTRUCTIVE_OPERATION("诱导破坏性操作", 50),

    /**
     * 可疑脚本片段。代码生成场景本身就需要 script/localStorage 等前端技术，
     * 因此该类权重最低，且不会单独作为拒绝依据。
     */
    SUSPICIOUS_CODE("可疑脚本片段", 25),

    /**
     * 异常编码混淆：零宽字符、双向控制符、Cc 控制字符、全角 ASCII 等规避手段。
     */
    ENCODING_ABUSE("异常编码混淆", 35),

    /**
     * 超长输入：清洗后码点数超过配置上限，直接按满分拒绝。
     */
    OVERSIZE_INPUT("超长输入", 80);

    /**
     * 类别中文名，用于日志和面向用户的拦截原因。
     */
    private final String text;

    /**
     * 默认风险权重，范围 0-100。
     */
    private final int weight;

    PromptRiskCategory(String text, int weight) {
        this.text = text;
        this.weight = weight;
    }
}
