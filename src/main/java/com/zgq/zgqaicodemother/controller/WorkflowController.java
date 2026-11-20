package com.zgq.zgqaicodemother.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.common.ResultUtils;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.exception.ThrowUtils;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.security.prompt.PromptReviewResult;
import com.zgq.zgqaicodemother.security.prompt.PromptReviewService;
import com.zgq.zgqaicodemother.security.prompt.PromptScene;
import com.zgq.zgqaicodemother.security.ratelimit.RateLimit;
import com.zgq.zgqaicodemother.service.UserService;
import com.zgq.zgqaicodemother.workflow.model.WorkflowEvent;
import com.zgq.zgqaicodemother.workflow.model.WorkflowEventMapper;
import com.zgq.zgqaicodemother.workflow.model.WorkflowResult;
import com.zgq.zgqaicodemother.workflow.model.dto.WorkflowExecuteRequest;
import com.zgq.zgqaicodemother.workflow.service.WorkflowService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 工作流控制器。
 *
 * <p>共四个接口：同步执行、SSE 流式执行、流程图和渠道状态。所有接口都要求登录，执行接口
 * 还要求当前用户是应用创建者；权限和参数校验集中在工作流服务中完成。</p>
 *
 * <p>SSE 协议：每个节点完成时发送以节点事件类型命名的事件，data 为 JSON；最后发送
 * {@code workflow_completed}（结果里 success 区分成功失败）或 {@code workflow_error}。
 * 流不会以 Reactor error 结束，避免浏览器 EventSource 只显示 network error。</p>
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class WorkflowController {

    @Resource
    private WorkflowService workflowService;

    @Resource
    private UserService userService;

    @Resource
    private PromptReviewService promptReviewService;

    /**
     * 同步执行工作流。适合测试、脚本和后台任务，前端交互请使用 SSE 接口。
     *
     * @param executeRequest 应用 id 和可选提示词
     * @param request        HTTP 请求，用于读取登录态
     * @return 工作流最终结果
     */
    @PostMapping("/execute")
    @RateLimit(key = "workflow-execute", permits = 3, windowSeconds = 60)
    @RateLimit(key = "workflow-execute-hourly", permits = 20, windowSeconds = 3600)
    public BaseResponse<WorkflowResult> execute(@RequestBody WorkflowExecuteRequest executeRequest,
                                                HttpServletRequest request) {
        ThrowUtils.throwIf(executeRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        Long appId = executeRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        // 工作流会串行调用多个模型与外部工具，成本最高，提示词必须先通过安全检查。
        executeRequest.setPrompt(reviewPrompt(executeRequest.getPrompt()));
        User loginUser = userService.getLoginUser(request);
        log.info("收到工作流同步执行请求，appId={}，promptLength={}", appId, promptLength(executeRequest.getPrompt()));
        return ResultUtils.success(workflowService.execute(appId, executeRequest.getPrompt(), loginUser));
    }

    /**
     * 流式执行工作流（SSE）。
     *
     * @param appId   应用 id
     * @param prompt  本次需求，为空时使用应用初始化提示词
     * @param request HTTP 请求，用于读取登录态
     * @return SSE 事件流
     */
    @GetMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(key = "workflow-stream", permits = 5, windowSeconds = 60)
    @RateLimit(key = "workflow-stream-hourly", permits = 40, windowSeconds = 3600)
    public Flux<ServerSentEvent<String>> executeStream(@RequestParam Long appId,
                                                       @RequestParam(required = false) String prompt,
                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        String safePrompt = reviewPrompt(prompt);
        User loginUser = userService.getLoginUser(request);
        log.info("收到工作流流式执行请求，appId={}，promptLength={}", appId, promptLength(safePrompt));
        return workflowService.executeStream(appId, safePrompt, loginUser)
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.type())
                        .data(JSONUtil.toJsonStr(WorkflowEventMapper.toMap(event)))
                        .build())
                .onErrorResume(error -> {
                    // 流开始后不能再写普通 JSON 错误体，用 error 事件通知前端。
                    log.error("工作流 SSE 流异常，appId={}", appId, error);
                    String reason = error instanceof BusinessException && StrUtil.isNotBlank(error.getMessage())
                            ? error.getMessage() : "工作流执行中断，请稍后重试";
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", WorkflowEvent.TYPE_ERROR);
                    data.put("message", reason);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event(WorkflowEvent.TYPE_ERROR)
                            .data(JSONUtil.toJsonStr(data))
                            .build());
                });
    }

    /**
     * 获取工作流图的 Mermaid 文本，前端可以直接渲染流程说明。
     *
     * @param request HTTP 请求，用于校验登录态
     * @return Mermaid 文本
     */
    @GetMapping("/graph")
    public BaseResponse<String> graph(HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(workflowService.mermaidGraph());
    }

    /**
     * 获取工作流能力状态：渠道是否配置、并发上限、活跃执行数等。不返回任何密钥。
     *
     * @param request HTTP 请求，用于校验登录态
     * @return 状态信息
     */
    @GetMapping("/status")
    public BaseResponse<Map<String, Object>> status(HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(workflowService.status());
    }

    private static int promptLength(String prompt) {
        return prompt == null ? 0 : prompt.length();
    }

    /**
     * 审查工作流提示词。
     *
     * <p>提示词为空表示使用应用初始化提示词，此时不在这里审查（初始化提示词在创建应用时已经审查过），
     * 直接返回 null 让服务层回退。</p>
     *
     * @param prompt 客户端传入的提示词
     * @return 清洗后的安全提示词；入参为空时返回 null
     */
    private String reviewPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        PromptReviewResult review = promptReviewService.review(prompt, PromptScene.WORKFLOW);
        ThrowUtils.throwIf(!review.allowed(), ErrorCode.PROMPT_REJECTED, review.reason());
        return StrUtil.isBlank(review.text()) ? prompt : review.text();
    }
}
