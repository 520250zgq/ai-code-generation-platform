package com.zgq.zgqaicodemother.workflow.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 一次工作流执行的最终结果。
 *
 * <p>这是对外返回对象，因此不包含服务器的绝对路径、模型密钥或内部异常堆栈。生成目录和构建目录
 * 都以“应用生成根目录”为基准返回相对路径，前端需要预览时使用应用 id 访问
 * {@code /static/{codeGenType}_{appId}}。</p>
 */
public record WorkflowResult(
        String workflowId,
        Long appId,
        boolean success,
        String currentStep,
        String generationType,
        String generatedCodeDir,
        String buildResultDir,
        int imageCount,
        int enhancedPromptLength,
        List<ImageResource> images,
        List<String> warnings,
        String errorMessage,
        long elapsedMs) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
