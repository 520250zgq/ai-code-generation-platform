package com.zgq.zgqaicodemother.workflow.demo;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 工作流开发第 3、4 步的示例：引入状态，并用假数据模拟状态流转。
 *
 * <p>与 {@link WorkflowDraft} 的区别是每个节点都会读写 {@link WorkflowContext}，条件边也真正
 * 读取状态中的路由结果。此实现只使用内存假数据，不访问模型、文件系统或外部接口，适合作为
 * 单元测试和新人理解工作流状态流转的入口。</p>
 */
@Slf4j
public class StatefulWorkflowDemo {

    /**
     * 创建带状态的模拟工作流。
     *
     * @return 已编译的工作流
     */
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            return new MessagesStateGraph<String>()
                    .addNode(WorkflowNodes.IMAGE_COLLECTOR, node_async(this::collectFakeImages))
                    .addNode(WorkflowNodes.PROMPT_ENHANCER, node_async(this::enhancePrompt))
                    .addNode(WorkflowNodes.ROUTER, node_async(this::route))
                    .addNode(WorkflowNodes.HTML_CODE_GENERATOR, node_async(state -> generate(state, CodeGenTypeEnum.HTML)))
                    .addNode(WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                            node_async(state -> generate(state, CodeGenTypeEnum.MULTI_FILE)))
                    .addNode(WorkflowNodes.VUE_CODE_GENERATOR,
                            node_async(state -> generate(state, CodeGenTypeEnum.VUE_PROJECT)))
                    .addNode(WorkflowNodes.PROJECT_BUILDER, node_async(this::build))
                    .addEdge(START, WorkflowNodes.IMAGE_COLLECTOR)
                    .addEdge(WorkflowNodes.IMAGE_COLLECTOR, WorkflowNodes.PROMPT_ENHANCER)
                    .addEdge(WorkflowNodes.PROMPT_ENHANCER, WorkflowNodes.ROUTER)
                    .addConditionalEdges(WorkflowNodes.ROUTER,
                            edge_async(this::routeDecision),
                            Map.of(
                                    CodeGenTypeEnum.HTML.getValue(), WorkflowNodes.HTML_CODE_GENERATOR,
                                    CodeGenTypeEnum.MULTI_FILE.getValue(), WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                                    CodeGenTypeEnum.VUE_PROJECT.getValue(), WorkflowNodes.VUE_CODE_GENERATOR))
                    .addEdge(WorkflowNodes.HTML_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.MULTI_FILE_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.VUE_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.PROJECT_BUILDER, END)
                    .compile();
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模拟工作流创建失败：" + e.getMessage());
        }
    }

    /**
     * 执行模拟工作流并返回最终状态。
     *
     * @param prompt 用户需求
     * @return 最终上下文
     */
    public WorkflowContext execute(String prompt) {
        WorkflowContext initial = WorkflowContext.builder()
                .workflowId(UUID.randomUUID().toString())
                .originalPrompt(prompt)
                .currentStep("初始化")
                .startedAt(LocalDateTime.now())
                .build();
        WorkflowContext latest = initial;
        for (NodeOutput<MessagesState<String>> output : createWorkflow().stream(WorkflowState.initialInput(initial))) {
            WorkflowContext context = WorkflowState.get(output);
            if (context != null) {
                latest = context;
                log.info("[模拟工作流] 节点 {} 完成，currentStep={}", output.node(), context.getCurrentStep());
            }
        }
        latest.finish();
        return latest;
    }

    private Map<String, Object> collectFakeImages(MessagesState<String> state) {
        WorkflowContext context = requireContext(state);
        context.setImages(List.of(
                ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("模拟的科技办公场景图片")
                        .url("https://images.example.com/fake-office.jpg")
                        .source("fake")
                        .build(),
                ImageResource.builder()
                        .category(ImageCategoryEnum.ILLUSTRATION)
                        .description("模拟的团队协作插画")
                        .url("https://images.example.com/fake-team.svg")
                        .source("fake")
                        .build()));
        context.setCurrentStep("图片收集（模拟）");
        return WorkflowState.save(context);
    }

    private Map<String, Object> enhancePrompt(MessagesState<String> state) {
        WorkflowContext context = requireContext(state);
        StringBuilder builder = new StringBuilder(context.getOriginalPrompt());
        builder.append("\n\n## 可用图片素材\n");
        for (ImageResource image : context.getImages()) {
            builder.append("- ").append(image.categoryText()).append("：")
                    .append(image.getDescription()).append("（").append(image.getUrl()).append("）\n");
        }
        context.setEnhancedPrompt(builder.toString());
        context.setCurrentStep("提示词增强（模拟）");
        return WorkflowState.save(context);
    }

    private Map<String, Object> route(MessagesState<String> state) {
        WorkflowContext context = requireContext(state);
        String prompt = context.getOriginalPrompt() == null ? "" : context.getOriginalPrompt();
        CodeGenTypeEnum type;
        if (prompt.toLowerCase().contains("vue")) {
            type = CodeGenTypeEnum.VUE_PROJECT;
        } else if (prompt.contains("多文件")) {
            type = CodeGenTypeEnum.MULTI_FILE;
        } else {
            type = CodeGenTypeEnum.HTML;
        }
        context.setGenerationType(type);
        context.setCurrentStep("智能路由（模拟）");
        return WorkflowState.save(context);
    }

    private String routeDecision(MessagesState<String> state) {
        WorkflowContext context = requireContext(state);
        CodeGenTypeEnum type = context.getGenerationType();
        return type == null ? CodeGenTypeEnum.HTML.getValue() : type.getValue();
    }

    private Map<String, Object> generate(MessagesState<String> state, CodeGenTypeEnum type) {
        WorkflowContext context = requireContext(state);
        if (context.getGenerationType() != type) {
            throw new IllegalStateException("模拟生成节点收到的类型与路由结果不一致");
        }
        // 模拟目录只使用相对路径，不触碰真实文件系统。
        context.setGeneratedCodeDir("tmp/code_output/" + type.getValue() + "_0");
        context.setCurrentStep("代码生成（模拟：" + type.getText() + "）");
        return WorkflowState.save(context);
    }

    private Map<String, Object> build(MessagesState<String> state) {
        WorkflowContext context = requireContext(state);
        CodeGenTypeEnum type = context.getGenerationType();
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            context.setBuildResultDir(context.getGeneratedCodeDir() + "/dist");
            context.setBuildSuccess(true);
        } else {
            context.setBuildResultDir(context.getGeneratedCodeDir());
            context.setBuildSuccess(true);
        }
        context.setCurrentStep("项目构建（模拟）");
        return WorkflowState.save(context);
    }

    private static WorkflowContext requireContext(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.get(state);
        if (context == null) {
            throw new IllegalStateException("工作流状态中缺少 WorkflowContext");
        }
        return context;
    }
}
