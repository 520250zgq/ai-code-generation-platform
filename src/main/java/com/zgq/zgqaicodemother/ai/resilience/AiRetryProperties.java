package com.zgq.zgqaicodemother.ai.resilience;

import lombok.Data;

import java.time.Duration;

/**
 * AI 调用重试（护轨）配置。
 *
 * <p>模型调用偶发超时、限流和 5xx 是常态。直接失败会让用户重新描述需求、重新消耗 token；
 * 无条件重试又可能把已经输出给用户的内容重复一遍，或者在参数错误、权限错误上做无意义的重试。
 * 因此重试必须带“护轨”：只在安全窗口内、只对可恢复错误、按退避加抖动进行。</p>
 */
@Data
public class AiRetryProperties {

    /**
     * 是否启用重试护轨。关闭后行为与优化前完全一致（一次失败即失败）。
     */
    private boolean enabled = true;

    /**
     * 单次调用的最大尝试次数（含首次）。1 表示不重试，2 表示失败后最多再试一次。
     *
     * <p>代码生成单次成本较高，默认只多试一次；追求更高成功率时应优先排查供应商侧稳定性。</p>
     */
    private int maxAttempts = 2;

    /**
     * 首次退避时间，之后按指数增长。
     */
    private Duration initialBackoff = Duration.ofMillis(500);

    /**
     * 单次退避上限，避免指数增长导致用户长时间无响应。
     */
    private Duration maxBackoff = Duration.ofSeconds(4);

    /**
     * 退避抖动系数（0~1）。多实例同时重试会造成“重试风暴”，抖动可以把请求打散。
     */
    private double jitter = 0.3;
}
