package com.zgq.zgqaicodemother.workflow;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.node.CodeQualityCheckNode;
import com.zgq.zgqaicodemother.workflow.node.HtmlCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ImageCollectorNode;
import com.zgq.zgqaicodemother.workflow.node.MultiFileCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ProjectBuilderNode;
import com.zgq.zgqaicodemother.workflow.node.PromptEnhancerNode;
import com.zgq.zgqaicodemother.workflow.node.RouterNode;
import com.zgq.zgqaicodemother.workflow.node.VueCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContextSerializer;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * AI 网站生成工作流。
 *
 * <p>完整结构：</p>
 * <pre>
 * START
 *   -> image_collector      （AI 规划 + 并发调用图片工具）
 *   -> prompt_enhancer      （把图片素材关联到原始需求）
 *   -> router               （选择 HTML / 多文件 / Vue 模式）
 *        ├─ html_code_generator ────────┐
 *        ├─ multi_file_code_generator ──┤
 *        └─ vue_code_generator ─────────┤
 *   -> code_quality_check   （拼接代码 + 静态检查 + AI 复核）
 *        ├─ 通过 ─────────────> project_builder
 *        ├─ 不通过且还有额度 ─> 对应的生成节点（拼接错误列表修复）
 *        └─ 上游失败/额度用尽 > END
 *   -> project_builder      （Vue 构建，其他模式直接通过）
 *        ├─ 构建失败且还有额度 ─> 对应的生成节点（拼接构建错误修复）
 *        └─ 其他 ─────────────> END
 * </pre>
 *
 * <p>路由节点和代码生成节点使用条件边；三个生成节点互斥执行。质检节点和构建节点都可能把
 * 流程送回生成节点形成修复循环，修复次数由 {@code app.workflow.quality-check.max-repair-attempts}
 * 统一限制，避免模型与质检互相“踢皮球”导致无限循环。任一节点失败都会在上下文中写入错误，
 * 后续节点检测到错误后跳过，工作流仍在 END 正常结束，调用方可以拿到失败原因。</p>
 *
 * <p>编译后的图在 Spring 单例中复用。节点本身无可变请求状态，每次执行的状态由
 * {@link WorkflowContext} 独立承载，因此可以并发处理多个应用。</p>
 */
@Slf4j
@Component
public class CodeGenWorkflow {

    /**
     * 质检通过（或质检关闭）后进入项目构建。
     */
    private static final String ROUTE_BUILD = "build";

    /**
     * 流程终止。
     */
    private static final String ROUTE_END = "end";

    private final CompiledGraph<MessagesState<String>> compiledGraph;

    public CodeGenWorkflow(ImageCollectorNode imageCollectorNode,
                           PromptEnhancerNode promptEnhancerNode,
                           RouterNode routerNode,
                           HtmlCodeGeneratorNode htmlCodeGeneratorNode,
                           MultiFileCodeGeneratorNode multiFileCodeGeneratorNode,
                           VueCodeGeneratorNode vueCodeGeneratorNode,
                           CodeQualityCheckNode codeQualityCheckNode,
                           ProjectBuilderNode projectBuilderNode) {
        try {
            // 使用显式上下文序列化器，避免 Java 原生序列化在 DevTools / 插件类加载器下
            // 把 WorkflowContext 解析成不同 Class 实例，导致状态读取失败。
            ObjectStreamStateSerializer<MessagesState<String>> stateSerializer =
                    new ObjectStreamStateSerializer<>(MessagesState::new);
            stateSerializer.mapper().register(WorkflowContext.class, new WorkflowContextSerializer());
            this.compiledGraph = new MessagesStateGraph<>(stateSerializer)
                    .addNode(WorkflowNodes.IMAGE_COLLECTOR, imageCollectorNode.create())
                    .addNode(WorkflowNodes.PROMPT_ENHANCER, promptEnhancerNode.create())
                    .addNode(WorkflowNodes.ROUTER, routerNode.create())
                    .addNode(WorkflowNodes.HTML_CODE_GENERATOR, htmlCodeGeneratorNode.create())
                    .addNode(WorkflowNodes.MULTI_FILE_CODE_GENERATOR, multiFileCodeGeneratorNode.create())
                    .addNode(WorkflowNodes.VUE_CODE_GENERATOR, vueCodeGeneratorNode.create())
                    .addNode(WorkflowNodes.CODE_QUALITY_CHECK, codeQualityCheckNode.create())
                    .addNode(WorkflowNodes.PROJECT_BUILDER, projectBuilderNode.create())
                    .addEdge(START, WorkflowNodes.IMAGE_COLLECTOR)
                    .addEdge(WorkflowNodes.IMAGE_COLLECTOR, WorkflowNodes.PROMPT_ENHANCER)
                    .addEdge(WorkflowNodes.PROMPT_ENHANCER, WorkflowNodes.ROUTER)
                    .addConditionalEdges(WorkflowNodes.ROUTER,
                            edge_async(this::routeByGenerationType),
                            Map.of(
                                    CodeGenTypeEnum.HTML.getValue(), WorkflowNodes.HTML_CODE_GENERATOR,
                                    CodeGenTypeEnum.MULTI_FILE.getValue(), WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                                    CodeGenTypeEnum.VUE_PROJECT.getValue(), WorkflowNodes.VUE_CODE_GENERATOR))
                    // 生成完成后先质检，再决定是否需要回到生成节点修复。
                    .addEdge(WorkflowNodes.HTML_CODE_GENERATOR, WorkflowNodes.CODE_QUALITY_CHECK)
                    .addEdge(WorkflowNodes.MULTI_FILE_CODE_GENERATOR, WorkflowNodes.CODE_QUALITY_CHECK)
                    .addEdge(WorkflowNodes.VUE_CODE_GENERATOR, WorkflowNodes.CODE_QUALITY_CHECK)
                    .addConditionalEdges(WorkflowNodes.CODE_QUALITY_CHECK,
                            edge_async(this::routeAfterQualityCheck),
                            Map.of(
                                    ROUTE_BUILD, WorkflowNodes.PROJECT_BUILDER,
                                    ROUTE_END, END,
                                    CodeGenTypeEnum.HTML.getValue(), WorkflowNodes.HTML_CODE_GENERATOR,
                                    CodeGenTypeEnum.MULTI_FILE.getValue(), WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                                    CodeGenTypeEnum.VUE_PROJECT.getValue(), WorkflowNodes.VUE_CODE_GENERATOR))
                    // 构建失败等价于无法正常打包，在额度内同样回到生成节点修复。
                    .addConditionalEdges(WorkflowNodes.PROJECT_BUILDER,
                            edge_async(this::routeAfterBuild),
                            Map.of(
                                    ROUTE_END, END,
                                    CodeGenTypeEnum.HTML.getValue(), WorkflowNodes.HTML_CODE_GENERATOR,
                                    CodeGenTypeEnum.MULTI_FILE.getValue(), WorkflowNodes.MULTI_FILE_CODE_GENERATOR,
                                    CodeGenTypeEnum.VUE_PROJECT.getValue(), WorkflowNodes.VUE_CODE_GENERATOR))
                    .compile();
        } catch (GraphStateException e) {
            // 图配置错误属于代码缺陷，应用启动阶段就应该暴露。
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 工作流初始化失败：" + e.getMessage());
        }
        log.info("AI 工作流初始化完成，节点数={}", WorkflowNodes.ORDERED_NODES.size());
    }

    /**
     * 同步执行工作流，返回最终上下文。
     *
     * @param initialContext 初始上下文
     * @return 最终上下文；图异常时抛出运行时异常，由服务层兜底
     */
    public WorkflowContext execute(WorkflowContext initialContext) {
        WorkflowContext latest = initialContext;
        for (NodeOutput<MessagesState<String>> output
                : compiledGraph.stream(WorkflowState.initialInput(initialContext))) {
            WorkflowContext context = WorkflowState.get(output);
            if (context != null) {
                latest = context;
            }
        }
        return latest;
    }

    /**
     * 获取图的 Mermaid 表示，用于接口展示或文档。
     *
     * @return Mermaid 文本
     */
    public String mermaidGraph() {
        GraphRepresentation representation = compiledGraph.getGraph(GraphRepresentation.Type.MERMAID);
        return representation.content();
    }

    /**
     * 暴露编译后的图，便于测试或后续接入 LangGraph Studio。
     *
     * @return 编译后的工作流
     */
    public CompiledGraph<MessagesState<String>> compiledGraph() {
        return compiledGraph;
    }

    /**
     * 路由决策：直接返回枚举 value，条件边映射表根据 value 选择生成节点。
     */
    private String routeByGenerationType(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.require(state);
        CodeGenTypeEnum type = context.getGenerationType();
        if (type == null) {
            log.warn("路由节点没有设置生成类型，回退到 HTML，workflowId={}", context.getWorkflowId());
            return CodeGenTypeEnum.HTML.getValue();
        }
        return type.getValue();
    }

    /**
     * 质检后的路由决策。
     *
     * <p>质检节点只有在“未通过且还有修复额度”时才会留下未通过结果而不写错误，因此这里
     * 看到未通过结果就说明应该回到对应的生成节点；上游失败或额度用尽时上下文中已有错误，
     * 直接结束，避免继续构建一个必然失败的产物。</p>
     */
    private String routeAfterQualityCheck(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.require(state);
        if (context.hasError()) {
            return ROUTE_END;
        }
        if (context.hasQualityIssues()) {
            CodeGenTypeEnum type = context.getGenerationType();
            if (type != null) {
                log.info("代码质检未通过，返回 {} 节点修复，workflowId={}，attempt={}",
                        type.getValue(), context.getWorkflowId(), context.getRepairAttempts());
                return type.getValue();
            }
        }
        return ROUTE_BUILD;
    }

    /**
     * 构建后的路由决策。
     *
     * <p>构建失败且还有修复额度时，构建节点只记录 {@code buildFailureMessage} 并占用一次修复
     * 额度；此时回到对应生成节点，把构建错误拼进修复提示词。构建成功、非 Vue 模式或额度用尽
     * 都直接结束。</p>
     */
    private String routeAfterBuild(MessagesState<String> state) {
        WorkflowContext context = WorkflowState.require(state);
        if (context.hasError()) {
            return ROUTE_END;
        }
        CodeGenTypeEnum type = context.getGenerationType();
        if (type != null && StrUtil.isNotBlank(context.getBuildFailureMessage())) {
            log.info("项目构建失败，返回 {} 节点修复，workflowId={}，attempt={}",
                    type.getValue(), context.getWorkflowId(), context.getRepairAttempts());
            return type.getValue();
        }
        return ROUTE_END;
    }
}
