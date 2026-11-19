package com.zgq.zgqaicodemother.workflow.demo;

import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态模拟工作流测试：验证状态在各节点间传递、条件边读取状态以及最终产物。
 */
class StatefulWorkflowDemoTest {

    @ParameterizedTest
    @CsvSource({
            "做一个企业官网, HTML, html_0",
            "做一个多文件的留言板, MULTI_FILE, multi_file_0",
            "用 Vue 做一个作品集, VUE_PROJECT, vue_project_0"
    })
    void routesByFakeStateAndBuildsResult(String prompt, CodeGenTypeEnum expectedType, String expectedDirSuffix) {
        WorkflowContext context = new StatefulWorkflowDemo().execute(prompt);

        assertEquals(expectedType, context.getGenerationType());
        assertTrue(context.getGeneratedCodeDir().endsWith(expectedDirSuffix));
        assertTrue(context.getBuildResultDir().contains(expectedType.getValue()));
        assertTrue(context.isBuildSuccess());
        assertEquals("项目构建（模拟）", context.getCurrentStep());
        assertNotNull(context.getFinishedAt());
        assertFalse(context.hasError());
    }

    @Test
    void enhancedPromptContainsCollectedImageUrls() {
        WorkflowContext context = new StatefulWorkflowDemo().execute("做一个企业官网");

        assertEquals(2, context.imageCount());
        assertTrue(context.getEnhancedPrompt().contains("https://images.example.com/fake-office.jpg"));
        assertTrue(context.getEnhancedPrompt().contains("可用图片素材"));
    }

    @Test
    void vueBranchProducesDistDirectory() {
        WorkflowContext context = new StatefulWorkflowDemo().execute("用 Vue 做一个作品集");

        assertTrue(context.getBuildResultDir().endsWith("/dist"));
    }
}
