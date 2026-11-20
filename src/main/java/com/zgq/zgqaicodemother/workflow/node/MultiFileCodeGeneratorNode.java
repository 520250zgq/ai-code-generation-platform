package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.WorkflowNodes;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.tools.CodeBundleTool;
import org.springframework.stereotype.Component;

/**
 * 原生多文件（HTML + CSS + JavaScript）模式代码生成节点。
 */
@Component
public class MultiFileCodeGeneratorNode extends AbstractCodeGeneratorNode {

    public MultiFileCodeGeneratorNode(AiCodeGeneratorFacade aiCodeGeneratorFacade,
                                      WorkflowProperties properties,
                                      CodeBundleTool codeBundleTool) {
        super(aiCodeGeneratorFacade, properties, codeBundleTool);
    }

    @Override
    protected CodeGenTypeEnum type() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    public String nodeId() {
        return WorkflowNodes.MULTI_FILE_CODE_GENERATOR;
    }
}
