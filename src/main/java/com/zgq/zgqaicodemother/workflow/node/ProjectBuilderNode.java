package com.zgq.zgqaicodemother.workflow.node;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 项目构建工作节点。
 *
 * <p>HTML / 多文件模式生成的是可直接浏览的静态文件，不需要构建；Vue 工程需要执行
 * {@code npm install} 和 {@code npm run build}，构建成功后才得到可预览的 dist 目录。</p>
 *
 * <p>构建耗时较长，工作流服务会把整个图放在虚拟线程中执行，不会占用 Web 请求线程。
 * 构建失败只写入上下文错误，不抛出异常，保证调用方仍能拿到源码目录和失败原因。</p>
 */
@Slf4j
@Component
public class ProjectBuilderNode {

    private final VueProjectBuilder vueProjectBuilder;

    public ProjectBuilderNode(VueProjectBuilder vueProjectBuilder) {
        this.vueProjectBuilder = vueProjectBuilder;
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
            context.setCurrentStep("项目构建跳过（上游失败）");
            log.info("上游节点失败，跳过项目构建，workflowId={}", context.getWorkflowId());
            return WorkflowState.save(context);
        }
        String generatedCodeDir = context.getGeneratedCodeDir();
        CodeGenTypeEnum type = context.getGenerationType();
        if (StrUtil.isBlank(generatedCodeDir) || type == null) {
            context.fail("项目构建失败", "缺少代码生成目录或生成类型");
            return WorkflowState.save(context);
        }
        Path codeDir = Path.of(generatedCodeDir).toAbsolutePath().normalize();
        if (!isInsideCodeOutputRoot(codeDir) || !Files.isDirectory(codeDir)) {
            context.fail("项目构建失败", "代码生成目录不存在或路径不合法");
            return WorkflowState.save(context);
        }
        if (type != CodeGenTypeEnum.VUE_PROJECT) {
            context.setBuildResultDir(codeDir.toString());
            context.setBuildSuccess(true);
            context.setCurrentStep("项目构建完成（静态文件无需构建）");
            log.info("静态网站无需构建，workflowId={}，dir={}", context.getWorkflowId(), codeDir);
            return WorkflowState.save(context);
        }
        if (!Files.isRegularFile(codeDir.resolve("package.json"))) {
            context.fail("项目构建失败", "Vue 工程缺少 package.json");
            return WorkflowState.save(context);
        }
        context.setCurrentStep("Vue 工程构建中");
        long startNanos = System.nanoTime();
        try {
            boolean success = vueProjectBuilder.buildProject(codeDir.toString());
            Path distDir = codeDir.resolve("dist").normalize();
            if (!success || !Files.isDirectory(distDir)) {
                context.setBuildSuccess(false);
                context.setBuildResultDir(codeDir.toString());
                context.fail("项目构建失败", "Vue 工程构建失败，请查看服务端构建日志");
                log.error("Vue 工程构建失败，workflowId={}，dir={}", context.getWorkflowId(), codeDir);
                return WorkflowState.save(context);
            }
            context.setBuildSuccess(true);
            context.setBuildResultDir(distDir.toString());
            context.setCurrentStep("Vue 工程构建完成");
            log.info("Vue 工程构建节点完成，workflowId={}，耗时={}ms，dist={}",
                    context.getWorkflowId(), (System.nanoTime() - startNanos) / 1_000_000, distDir);
        } catch (Exception e) {
            context.setBuildSuccess(false);
            context.setBuildResultDir(codeDir.toString());
            context.fail("项目构建失败", "Vue 工程构建异常，请查看服务端构建日志");
            log.error("Vue 工程构建节点异常，workflowId={}，dir={}",
                    context.getWorkflowId(), codeDir, e);
        }
        return WorkflowState.save(context);
    }

    /**
     * 只允许操作应用代码根目录下的工程，防止上下文被篡改后构建任意目录。
     */
    private static boolean isInsideCodeOutputRoot(Path codeDir) {
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        return codeDir.startsWith(root) && !codeDir.equals(root);
    }

    /**
     * 节点 id，供图定义和测试引用。
     *
     * @return 节点 id
     */
    public static String nodeId() {
        return WorkflowNodes.PROJECT_BUILDER;
    }
}
