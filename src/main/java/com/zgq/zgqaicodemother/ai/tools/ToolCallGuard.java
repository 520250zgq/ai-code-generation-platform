package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 工具调用循环保护器。
 *
 * <p>LangChain4j 的 {@code maxSequentialToolsInvocations} 只限制“连续调用次数”，无法识别
 * “同一文件被反复写入”这类语义层面的死循环：模型每次都能拿到一次“成功”结果，于是继续写同一个文件，
 * 直到触发次数上限，用户已经等了几分钟却只得到一个失败提示。</p>
 *
 * <p>本类按“应用 + 单轮生成”维护调用计数，在文件工具真正执行前判定：</p>
 * <ul>
 *   <li>总次数超限 → 抛 {@link ToolCallLimitException} 终止本轮；</li>
 *   <li>同一工具 + 同一目标重复超过阈值 → 返回提示，不执行文件操作，让模型把注意力转回未完成的文件；</li>
 *   <li>模型继续重复超过容忍次数 → 抛异常终止，防止无限“提示—重复”。</li>
 * </ul>
 *
 * <p>状态以 appId 为键，由调用方在每轮生成开始/结束时通过 {@link #startTurn(Long)} /
 * {@link #finishTurn(Long)} 维护；没有登记轮次时（例如工具被单独调用、单元测试直接 new）
 * 不做任何限制，保证工具本身仍然可独立使用。</p>
 */
@Slf4j
public class ToolCallGuard {

    /**
     * 未启用保护时使用的无操作实例；工具在 Spring 之外被直接构造时会回退到它。
     */
    private static final ToolCallGuard DISABLED = new ToolCallGuard(disabledProperties());

    private final ToolCallProperties properties;

    /**
     * appId 到当前轮次状态的映射。ConcurrentHashMap 保证并发工具调用（不同应用）互不阻塞。
     */
    private final Map<Long, TurnState> turns = new ConcurrentHashMap<>();

    public ToolCallGuard(ToolCallProperties properties) {
        this.properties = Objects.requireNonNull(properties, "工具调用保护配置不能为空");
    }

    /**
     * 无保护实例：不在 Spring 容器内构造工具时使用，例如单元测试直接 new FileWriteTool()。
     *
     * @return 不限制调用的保护器
     */
    public static ToolCallGuard disabled() {
        return DISABLED;
    }

    /**
     * 开始一轮新的生成，重置该应用的调用计数。
     *
     * <p>同一应用不允许多轮并发生成（由 AiConcurrencyLimiter 保证），因此这里直接覆盖旧状态；
     * 如果旧轮次因为客户端断连没有正常结束，覆盖同时也完成了清理。</p>
     *
     * @param appId 应用 id，非法值会被忽略
     */
    public void startTurn(Long appId) {
        if (!properties.isEnabled() || appId == null) {
            return;
        }
        cleanupIfNeeded();
        turns.put(appId, new TurnState(System.currentTimeMillis()));
    }

    /**
     * 结束一轮生成并清理状态。
     *
     * @param appId 应用 id
     */
    public void finishTurn(Long appId) {
        if (appId == null) {
            return;
        }
        turns.remove(appId);
    }

    /**
     * 工具执行前的判定。
     *
     * @param appId    应用 id
     * @param toolName 工具名称，例如 writeFile
     * @param subject  操作对象，通常是文件相对路径；可为空
     * @return 允许或拒绝（附带给模型的提示）
     * @throws ToolCallLimitException 总次数或重复次数超过上限，需要立即终止本轮生成
     */
    public ToolCallDecision beforeCall(Long appId, String toolName, String subject) {
        if (!properties.isEnabled() || appId == null || StrUtil.isBlank(toolName)) {
            return ToolCallDecision.allow();
        }
        TurnState state = turns.get(appId);
        if (state == null) {
            // 没有登记轮次时不参与统计，避免把一次性工具调用误判成循环。
            return ToolCallDecision.allow();
        }
        int totalCalls = state.totalCalls.incrementAndGet();
        int maxCalls = Math.max(1, properties.getMaxCallsPerTurn());
        if (totalCalls > maxCalls) {
            throw new ToolCallLimitException("工具调用次数超过上限（" + maxCalls
                    + " 次），已停止生成，请简化需求后重试");
        }
        String subjectKey = toolName + ':' + StrUtil.blankToDefault(subject, "-");
        int repeats = state.subjectCalls
                .computeIfAbsent(subjectKey, key -> new AtomicInteger())
                .incrementAndGet();
        int maxRepeats = Math.max(1, properties.getMaxRepeatsPerSubject());
        if (repeats <= maxRepeats) {
            return ToolCallDecision.allow();
        }
        int maxDenials = Math.max(0, properties.getMaxRepeatedDenials());
        if (repeats <= maxRepeats + maxDenials) {
            log.warn("检测到工具重复调用，已拦截本次执行，appId={}，tool={}，subject={}，重复次数={}",
                    appId, toolName, subject, repeats);
            return ToolCallDecision.deny(buildHint(toolName, subject, repeats, maxRepeats));
        }
        throw new ToolCallLimitException("检测到对「" + StrUtil.blankToDefault(subject, toolName)
                + "」的重复工具调用（" + repeats + " 次），已停止生成，请检查需求或稍后重试");
    }

    /**
     * 当前轮次已执行的工具调用次数，供监控和测试使用。
     *
     * @param appId 应用 id
     * @return 调用次数；没有进行中的轮次时返回 0
     */
    public int callCount(Long appId) {
        TurnState state = appId == null ? null : turns.get(appId);
        return state == null ? 0 : state.totalCalls.get();
    }

    /**
     * 当前跟踪的轮次数量，用于确认状态不会随请求无限增长。
     *
     * @return 跟踪中的轮次数
     */
    public int trackedTurns() {
        return turns.size();
    }

    /**
     * 拼接面向模型的中文提示：说明原因，并给出明确的下一步动作。
     */
    private static String buildHint(String toolName, String subject, int repeats, int maxRepeats) {
        String target = StrUtil.blankToDefault(subject, "同一目标");
        return "该操作已重复执行 " + repeats + " 次（同一轮最多允许 " + maxRepeats + " 次），本次不再执行，"
                + "以避免无效循环。请不要再次调用 " + toolName + " 处理「" + target + "」："
                + "如果已经写入完成，请继续创建或修改其它文件；如果确实需要调整内容，"
                + "请先用 readFile 确认当前内容，再用 modifyFile 做局部修改。";
    }

    /**
     * 清理超时轮次；超过容量上限时再兜底淘汰最早的条目。
     *
     * <p>正常情况下每轮都会调用 {@link #finishTurn(Long)}，这里只处理客户端断连、
     * 进程被强制中止等异常路径，因此检查成本可以很低。</p>
     */
    private void cleanupIfNeeded() {
        int maxTracked = Math.max(1, properties.getMaxTrackedTurns());
        if (turns.size() < maxTracked) {
            return;
        }
        long expireBefore = System.currentTimeMillis() - Math.max(1L, properties.getTurnTtl().toMillis());
        turns.entrySet().removeIf(entry -> entry.getValue().startedAt < expireBefore);
        // 仍然超限说明存在大量并发轮次，按插入顺序淘汰最旧的一条，保证内存有界。
        Iterator<Long> iterator = turns.keySet().iterator();
        while (turns.size() >= maxTracked && iterator.hasNext()) {
            Long evicted = iterator.next();
            iterator.remove();
            log.warn("工具调用保护状态超过上限，已淘汰最旧轮次，appId={}", evicted);
        }
    }

    /**
     * 默认关闭全部限制的配置对象，仅用于 {@link #disabled()}。
     */
    private static ToolCallProperties disabledProperties() {
        ToolCallProperties properties = new ToolCallProperties();
        properties.setEnabled(false);
        return properties;
    }

    /**
     * 单轮生成内的调用统计。
     */
    private static final class TurnState {

        /**
         * 轮次开始时间，用于清理异常残留。
         */
        private final long startedAt;

        /**
         * 本轮工具调用总次数。
         */
        private final AtomicInteger totalCalls = new AtomicInteger();

        /**
         * 按“工具名 + 操作对象”统计的重复次数。
         */
        private final Map<String, AtomicInteger> subjectCalls = new ConcurrentHashMap<>();

        private TurnState(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    /**
     * 工具调用超过保护上限时抛出，用于中止 LangChain4j 的工具调用循环。
     *
     * <p>继承 {@link RuntimeException} 而不是业务异常：工具层不应该依赖 Web 层的错误码，
     * 由流式处理器转换为面向用户的业务提示。</p>
     */
    public static final class ToolCallLimitException extends RuntimeException {

        public ToolCallLimitException(String message) {
            super(message);
        }
    }
}
