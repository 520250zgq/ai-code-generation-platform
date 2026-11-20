package com.zgq.zgqaicodemother.workflow.node;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.ai.CodeQualityCheckService;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ProjectCode;
import com.zgq.zgqaicodemother.workflow.model.QualityCheckResult;
import com.zgq.zgqaicodemother.workflow.model.QualityCheckReview;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import com.zgq.zgqaicodemother.workflow.support.CodeQualityChecks;
import com.zgq.zgqaicodemother.workflow.support.CodeQualityPromptSupport;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import com.zgq.zgqaicodemother.workflow.tools.CodeBundleTool;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码质检工作节点。
 *
 * <p>节点位于代码生成之后、项目构建之前，分为三层检查：</p>
 * <ol>
 *   <li>用 {@link CodeBundleTool} 读取并拼接生成目录下的所有代码文件；</li>
 *   <li>执行确定性静态检查（入口文件、代码围栏、本地资源引用、Vue 工程结构、导入路径）；</li>
 *   <li>调用 {@link CodeQualityCheckService} 让 AI 复核语法、引用、可打包性和运行时错误。</li>
 * </ol>
 *
 * <p>质检不通过时节点只记录结果并占用一次修复额度，由 {@code CodeGenWorkflow} 的条件边
 * 回到对应的代码生成节点；额度用尽后才写入阻断性错误。AI 复核失败属于非致命问题，
 * 节点会降级为“仅静态检查”并把告警写回上下文，不让模型故障卡住整个工作流。</p>
 */
@Slf4j
@Component
public class CodeQualityCheckNode {

    /**
     * 写入上下文错误信息时最多展示的错误条数。
     */
    private static final int ERROR_SUMMARY_ITEMS = 3;

    /**
     * 单条 AI 错误信息的最大长度。
     */
    private static final int MAX_AI_ERROR_LENGTH = 300;

    private final CodeBundleTool codeBundleTool;

    private final CodeQualityCheckService codeQualityCheckService;

    private final WorkflowProperties properties;

    public CodeQualityCheckNode(CodeBundleTool codeBundleTool,
                                CodeQualityCheckService codeQualityCheckService,
                                WorkflowProperties properties) {
        this.codeBundleTool = codeBundleTool;
        this.codeQualityCheckService = codeQualityCheckService;
        this.properties = properties;
    }

    /**
     * 创建节点动作。
     *
     * @return LangGraph4j 节点动作
     */
    public AsyncNodeAction<MessagesState<String>> create() {
        return node_async(this::execute);
    }

    private Map<String, Object> execute(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.require(state);
        if (context.hasError()) {
            context.setCurrentStep("代码质检跳过（上游失败）");
            log.info("上游节点失败，跳过代码质检，workflowId={}", context.getWorkflowId());
            return WorkflowState.save(context);
        }
        CodeGenTypeEnum type = context.getGenerationType();
        if (StrUtil.isBlank(context.getGeneratedCodeDir()) || type == null) {
            context.fail("代码质检失败", "缺少代码生成目录或生成类型");
            return WorkflowState.save(context);
        }
        WorkflowProperties.QualityCheck config = properties.getQualityCheck();
        long startNanos = System.nanoTime();
        ProjectCode code;
        try {
            code = codeBundleTool.collect(context.getGeneratedCodeDir(), config);
        } catch (IllegalArgumentException e) {
            // 目录不存在或路径被篡改说明上下文已经不可信，属于阻断性错误。
            context.fail("代码质检失败", e.getMessage());
            log.error("代码质检无法读取代码目录，workflowId={}，error={}",
                    context.getWorkflowId(), e.getMessage());
            return WorkflowState.save(context);
        }
        context.setCurrentStep("代码质检中");
        if (!config.isEnabled()) {
            context.setQualityCheckResult(null);
            context.setCurrentStep("代码质检已关闭");
            log.info("代码质检未启用，跳过检查，workflowId={}", context.getWorkflowId());
            return WorkflowState.save(context);
        }

        List<String> staticErrors = CodeQualityChecks.check(code, type, config.getMaxErrors());
        QualityCheckReview review = reviewWithAi(context, type, code, staticErrors, config);
        QualityCheckResult result = buildResult(code, staticErrors, review, config,
                (System.nanoTime() - startNanos) / 1_000_000);
        context.setQualityCheckResult(result);
        code.getWarnings().forEach(context::addWarning);
        log.info("代码质检节点完成，workflowId={}，appId={}，type={}，valid={}，errors={}，files={}，耗时={}ms",
                context.getWorkflowId(), context.getAppId(), type.getValue(), result.passed(),
                result.errorCount(), result.getCheckedFiles().size(), result.getDurationMs());

        if (result.passed()) {
            context.setCurrentStep("代码质检通过（" + result.getCheckedFiles().size() + " 个文件）");
            return WorkflowState.save(context);
        }
        int maxRepairAttempts = Math.max(0, config.getMaxRepairAttempts());
        if (!context.canRepair(maxRepairAttempts)) {
            context.fail("代码质检未通过", buildFailureSummary(result));
            context.setCurrentStep("代码质检未通过（已达最大修复次数）");
            log.warn("代码质检未通过且没有剩余修复额度，workflowId={}，attempts={}，errors={}",
                    context.getWorkflowId(), context.getRepairAttempts(),
                    StrUtil.maxLength(String.join("；", result.getErrors()), 500));
            return WorkflowState.save(context);
        }
        context.recordRepairAttempt();
        context.setCurrentStep("代码质检未通过，返回生成节点修复（第 "
                + context.getRepairAttempts() + "/" + maxRepairAttempts + " 次）");
        log.info("代码质检未通过，准备返回代码生成节点修复，workflowId={}，attempt={}/{}, errors={}",
                context.getWorkflowId(), context.getRepairAttempts(), maxRepairAttempts,
                StrUtil.maxLength(String.join("；", result.getErrors()), 500));
        return WorkflowState.save(context);
    }

    /**
     * 调用 AI 复核。模型不可用时返回 null，由静态检查结果决定是否通过。
     */
    private QualityCheckReview reviewWithAi(WorkflowContext context, CodeGenTypeEnum type,
                                            ProjectCode code, List<String> staticErrors,
                                            WorkflowProperties.QualityCheck config) {
        if (code.isEmpty()) {
            // 没有代码可交给模型，静态检查已经给出明确错误。
            return null;
        }
        String request = CodeQualityPromptSupport.buildReviewRequest(
                type, context.getOriginalPrompt(), staticErrors, code, config.getMaxPromptChars());
        try {
            QualityCheckReview review = codeQualityCheckService.reviewCode(request);
            if (review == null) {
                context.addWarning("代码质检 AI 复核未返回结果，已降级为静态检查");
            }
            return review;
        } catch (Exception e) {
            // 模型超时、限流或 JSON 解析失败都可能进入这里，不能因此阻断代码生成。
            log.warn("代码质检 AI 复核失败，降级为静态检查，workflowId={}：{}",
                    context.getWorkflowId(), e.getMessage());
            log.debug("代码质检 AI 复核失败详情", e);
            context.addWarning("代码质检 AI 复核失败，已降级为静态检查");
            return null;
        }
    }

    /**
     * 合并静态检查和 AI 复核结果，去重并限制条数。
     */
    private static QualityCheckResult buildResult(ProjectCode code, List<String> staticErrors,
                                                  QualityCheckReview review,
                                                  WorkflowProperties.QualityCheck config,
                                                  long durationMs) {
        Set<String> errorSet = new LinkedHashSet<>();
        staticErrors.forEach(error -> errorSet.add(error));
        List<String> suggestions = new ArrayList<>();
        if (review != null) {
            if (review.getErrors() != null) {
                review.getErrors().stream()
                        .filter(StrUtil::isNotBlank)
                        .map(error -> WorkflowImageSupport.sanitizeText(error, MAX_AI_ERROR_LENGTH))
                        .filter(StrUtil::isNotBlank)
                        .forEach(errorSet::add);
            }
            if (Boolean.FALSE.equals(review.getValid()) && errorSet.isEmpty()) {
                // 模型只说“不通过”却没给原因时补一条兜底错误，否则无法构造修复提示词。
                errorSet.add("AI 代码复核未通过，但未给出具体错误，请重新检查语法、引用和构建配置");
            }
            if (review.getSuggestions() != null) {
                review.getSuggestions().stream()
                        .filter(StrUtil::isNotBlank)
                        .map(suggestion -> WorkflowImageSupport.sanitizeText(suggestion, MAX_AI_ERROR_LENGTH))
                        .filter(StrUtil::isNotBlank)
                        .limit(Math.max(1, config.getMaxSuggestions()))
                        .forEach(suggestions::add);
            }
        }
        int maxErrors = Math.max(1, config.getMaxErrors());
        List<String> errors = new ArrayList<>(errorSet);
        if (errors.size() > maxErrors) {
            errors = new ArrayList<>(errors.subList(0, maxErrors));
            errors.add("问题数量超过上限，仅保留前 " + maxErrors + " 条");
        }
        boolean reviewRejected = review != null && Boolean.FALSE.equals(review.getValid());
        boolean valid = !reviewRejected && errors.isEmpty();
        return QualityCheckResult.builder()
                .valid(valid)
                .errors(errors)
                .suggestions(suggestions)
                .checkedFiles(code.filePaths())
                .checkedChars(code.totalChars())
                .truncated(code.isTruncated())
                .source(review == null ? "static" : "static+ai")
                .durationMs(durationMs)
                .build();
    }

    /**
     * 把错误列表压缩成可写入上下文的失败原因。
     */
    private static String buildFailureSummary(QualityCheckResult result) {
        List<String> errors = result.getErrors() == null ? List.of() : result.getErrors();
        StringBuilder summary = new StringBuilder("代码质检发现 ")
                .append(errors.size()).append(" 个问题：");
        for (int i = 0; i < Math.min(ERROR_SUMMARY_ITEMS, errors.size()); i++) {
            if (i > 0) {
                summary.append("；");
            }
            summary.append(errors.get(i));
        }
        return StrUtil.maxLength(summary.toString(), 1_000);
    }

    /**
     * 节点 id，供图定义和测试引用。
     *
     * @return 节点 id
     */
    public static String nodeId() {
        return WorkflowNodes.CODE_QUALITY_CHECK;
    }
}
