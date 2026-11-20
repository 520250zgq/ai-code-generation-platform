package com.zgq.zgqaicodemother.ai.resilience;

import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码输出护轨测试：完整性校验和服务器路径泄漏防护。
 */
class CodeOutputGuardTest {

    private static final String VALID_HTML =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\"><body><h1>作品集</h1></body></html>";

    @Test
    void acceptsCompleteHtmlPage() {
        assertDoesNotThrow(() -> CodeOutputGuard.validate(new HtmlCodeResult(VALID_HTML, "作品集")));
        assertDoesNotThrow(() -> CodeOutputGuard.validate(
                new MultiFileCodeResult(VALID_HTML, "body{margin:0}", "console.log(1)", "多文件")));
    }

    @Test
    void rejectsTruncatedOrEmptyHtml() {
        // HtmlCodeResult 的 JSON 构造器已经拒绝空 html，这里用 setter 构造“解析后的半成品”，
        // 覆盖模型输出被截断或字段为空的场景。
        HtmlCodeResult empty = new HtmlCodeResult();
        empty.setHtmlCode("");
        BusinessException emptyError = assertThrows(BusinessException.class,
                () -> CodeOutputGuard.validate(empty));
        assertTrue(emptyError.getMessage().contains("为空"), emptyError.getMessage());

        HtmlCodeResult truncated = new HtmlCodeResult();
        truncated.setHtmlCode("<html><body");
        BusinessException truncatedError = assertThrows(BusinessException.class,
                () -> CodeOutputGuard.validate(truncated));
        assertTrue(truncatedError.getMessage().contains("完整页面"), truncatedError.getMessage());

        // 只有很短的片段同样视为截断，避免把半成品写进预览目录。
        HtmlCodeResult fragment = new HtmlCodeResult();
        fragment.setHtmlCode("<html></html>");
        BusinessException fragmentError = assertThrows(BusinessException.class,
                () -> CodeOutputGuard.validate(fragment));
        assertTrue(fragmentError.getMessage().contains("完整页面"), fragmentError.getMessage());
    }

    @Test
    void rejectsHtmlLeakingServerOutputDirectory() {
        String leaked = VALID_HTML + "\n<!-- " + AppConstant.CODE_OUTPUT_ROOT_DIR + "/html_1 -->";
        BusinessException error = assertThrows(BusinessException.class,
                () -> CodeOutputGuard.validate(new HtmlCodeResult(leaked, "泄漏路径")));
        assertTrue(error.getMessage().contains("服务器文件路径"), error.getMessage());
    }

    @Test
    void rejectsNullResult() {
        assertThrows(BusinessException.class, () -> CodeOutputGuard.validate((HtmlCodeResult) null));
        assertThrows(BusinessException.class, () -> CodeOutputGuard.validate((MultiFileCodeResult) null));
    }
}
