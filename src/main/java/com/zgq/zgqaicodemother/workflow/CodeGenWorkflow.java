package com.zgq.zgqaicodemother.workflow;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
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
 *   -> project_builder      （Vue 构建，其他模式直接通过）
 *   -> END
 * </pre>
 *
 * <p>路由节点使用条件边，三个生成节点互斥执行；任一生成节点失败都会在上下文中写入错误，
 * 项目构建节点检测到错误后跳过构建，工作流仍在 END 正常结束，调用方可以拿到失败原因。</p>
 *
 * <p>编译后的图在 Spring 单例中复用。节点本身无可变请求状态，每次执行的状态由
 * {@link WorkflowContext} 独立承载，因此可以并发处理多个应用。</p>
 */
@Slf4j
@Component
public class CodeGenWorkflow {

    private final CompiledGraph<MessagesState<String>> compiledGraph;

    public CodeGenWorkflow(ImageCollectorNode imageCollectorNode,
                           PromptEnhancerNode promptEnhancerNode,
                           RouterNode routerNode,
                           HtmlCodeGeneratorNode htmlCodeGeneratorNode,
                           MultiFileCodeGeneratorNode multiFileCodeGeneratorNode,
                           VueCodeGeneratorNode vueCodeGeneratorNode,
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
                    .addEdge(WorkflowNodes.HTML_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.MULTI_FILE_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.VUE_CODE_GENERATOR, WorkflowNodes.PROJECT_BUILDER)
                    .addEdge(WorkflowNodes.PROJECT_BUILDER, END)
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
}
