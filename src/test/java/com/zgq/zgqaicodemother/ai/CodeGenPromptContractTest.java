package com.zgq.zgqaicodemother.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码生成提示词契约测试。
 *
 * <p>修改需求依赖提示词中的元素信息格式和“全量/增量”约束，这里用确定性断言锁定关键文案，
 * 避免后续调整提示词时无意破坏可视化编辑链路。</p>
 */
class CodeGenPromptContractTest {

    private static final String ELEMENT_BLOCK_TITLE = "【可视化编辑 - 当前选中元素】";

    @Test
    void htmlPromptRequiresFullRewriteForElementModification() throws IOException {
        String prompt = readPrompt("prompt/codegen-html-system-prompt.txt");

        assertTrue(prompt.contains(ELEMENT_BLOCK_TITLE), "HTML 提示词必须约定元素信息块格式");
        assertTrue(prompt.contains("只修改与用户要求相关的元素"), "必须限制修改范围");
        assertTrue(prompt.contains("完整的 HTML 文件"), "修改时必须重新输出完整 HTML");
        assertTrue(prompt.contains("最多输出 1 个 HTML 代码块"), "必须限制代码块数量，避免解析错误");
    }

    @Test
    void multiFilePromptRequiresThreeCompleteFiles() throws IOException {
        String prompt = readPrompt("prompt/codegen-multi-file-system-prompt.txt");

        assertTrue(prompt.contains(ELEMENT_BLOCK_TITLE), "多文件提示词必须约定元素信息块格式");
        assertTrue(prompt.contains("只修改与用户要求相关的元素"), "必须限制修改范围");
        assertTrue(prompt.contains("完整的 index.html、style.css、script.js"), "修改时必须输出三个完整文件");
        assertTrue(prompt.contains("每种语言最多 1 个代码块"), "必须限制代码块数量，避免解析错误");
    }

    @Test
    void vuePromptListsEveryToolAndRequiresReadBeforeModify() throws IOException {
        String prompt = readPrompt("prompt/codegen-vue-project-system-prompt.txt");

        for (String toolName : new String[]{"readDir", "readFile", "modifyFile", "writeFile", "deleteFile"}) {
            assertTrue(prompt.contains(toolName), "Vue 提示词必须列出工具：" + toolName);
        }
        assertTrue(prompt.contains(ELEMENT_BLOCK_TITLE), "Vue 提示词必须约定元素信息块格式");
        assertTrue(prompt.contains("修改文件前必须先读取"), "必须要求先读取再修改");
        assertTrue(prompt.contains("oldContent 必须从 readFile 结果中完整复制"), "必须约束局部替换的旧内容来源");
        assertTrue(prompt.contains("禁止凭记忆猜测文件内容"), "必须禁止凭记忆修改");
        assertTrue(prompt.contains("禁止在回答正文中输出完整文件内容"), "必须禁止在回答中重复输出完整代码");
        assertTrue(prompt.contains("停止调用工具"), "修改完成后必须停止工具调用");
    }

    @Test
    void qualityCheckPromptDefinesStructuredVerdictAndStrictErrors() throws IOException {
        String prompt = readPrompt("prompt/code-quality-check-system-prompt.txt");

        assertTrue(prompt.contains("\"valid\""), "质检提示词必须约定 valid 字段");
        assertTrue(prompt.contains("\"errors\""), "质检提示词必须约定 errors 字段");
        assertTrue(prompt.contains("\"suggestions\""), "质检提示词必须约定 suggestions 字段");
        assertTrue(prompt.contains("构建失败"), "必须要求检查能否正常打包");
        assertTrue(prompt.contains("不要重复报告"), "静态检查已列出的问题不能重复报告");
        assertTrue(prompt.contains("errors 每条必须包含文件名"), "错误必须可定位到文件");
    }

    private static String readPrompt(String resourcePath) throws IOException {
        try (InputStream inputStream = CodeGenPromptContractTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            assertNotNull(inputStream, "提示词资源不存在：" + resourcePath);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
