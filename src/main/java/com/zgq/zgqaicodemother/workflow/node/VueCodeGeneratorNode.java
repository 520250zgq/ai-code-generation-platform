package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import org.springframework.stereotype.Component;

/**
 * Vue 工程模式代码生成节点。
 *
 * <p>Vue 工程由模型通过文件工具逐个写入，构建由 {@link ProjectBuilderNode} 负责，因此这里以
 * {@code autoBuild=false} 调用代码生成外观类。</p>
 */
@Component
public class VueCodeGeneratorNode extends AbstractCodeGeneratorNode {

    public VueCodeGeneratorNode(AiCodeGeneratorFacade aiCodeGeneratorFacade, WorkflowProperties properties) {
        super(aiCodeGeneratorFacade, properties);
    }

    @Override
    protected CodeGenTypeEnum type() {
        return CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public String nodeId() {
        return WorkflowNodes.VUE_CODE_GENERATOR;
    }
}
