package com.zgq.zgqaicodemother.ai.tools;

import lombok.Data;

import java.time.Duration;

/**
 * AI 工具调用保护配置。
 *
 * <p>Vue 工程模式由模型通过工具逐步写文件。正常情况下一次生成会调用 10~30 次工具；
 * 但模型在幻觉、上下文被截断或收到相互矛盾的修复提示时，可能反复写同一个文件、
 * 反复读同一段内容，形成“工具调用死循环”，既浪费 token 又让用户长时间看不到结果。</p>
 *
 * <p>保护分三层，阈值全部可配置：</p>
 * <ol>
 *   <li>单轮总调用次数上限（硬兜底，超过直接终止）；</li>
 *   <li>同一工具对同一文件的重复次数上限：先返回可读提示让模型自我纠正；</li>
 *   <li>模型无视提示继续重复时，再升级为终止，避免“提示 → 再重复”无限循环。</li>
 * </ol>
 */
@Data
public class ToolCallProperties {

    /**
     * 是否启用工具调用保护。关闭后仍保留流式处理器的硬上限，
     * 但不再做重复调用检测，不建议在生产环境关闭。
     */
    private boolean enabled = true;

    /**
     * 单轮生成允许的工具调用总次数。
     *
     * <p>默认 60：一个中等规模 Vue 工程约 20~40 个文件，留出读取和修改的余量。
     * 上限过大无法及时止损，过小会让复杂工程被误杀。</p>
     */
    private int maxCallsPerTurn = 60;

    /**
     * 同一工具对同一目标（通常是文件路径）允许的重复次数。
     *
     * <p>超过后返回提示而不是执行，让模型看到“已经写过、请继续下一个文件”；
     * 正常的“写 → 读 → 改”流程每一环的目标不同，不会触发该阈值。</p>
     */
    private int maxRepeatsPerSubject = 2;

    /**
     * 重复调用被提示后仍继续重复的容忍次数，超过即终止本轮生成。
     */
    private int maxRepeatedDenials = 5;

    /**
     * 单轮状态的最长保留时间。客户端断连等异常路径可能没有正常结束轮次，
     * 超过该时间的状态会在下一轮开始时被清理，避免内存缓慢增长。
     */
    private Duration turnTtl = Duration.ofMinutes(30);

    /**
     * 同时跟踪的轮次上限，用于内存兜底保护。
     */
    private int maxTrackedTurns = 2000;
}
