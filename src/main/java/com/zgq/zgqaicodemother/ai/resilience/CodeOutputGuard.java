package com.zgq.zgqaicodemother.ai.resilience;

import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;

import java.util.Locale;

/**
 * 代码输出护轨。
 *
 * <p>模型返回的 JSON 能解析，并不代表内容可以直接落盘：输出可能被截断在 CSS 中间、
 * 可能整段是空模板，也可能把服务器绝对路径写进注释。这类问题如果不在保存前拦住，
 * 用户看到的将是一个白屏页面，排查成本远高于当场返回“生成不完整，请重试”。</p>
 *
 * <p>本类只做确定性、低成本的结构校验，不做语义审查（语义问题由工作流的质检节点处理）。</p>
 */
public final class CodeOutputGuard {

    /**
     * HTML 输出被视为“完整页面”的最小长度。低于该长度通常是被截断或只返回了占位文本。
     */
    static final int MIN_HTML_LENGTH = 40;

    /**
     * 保存目录的规范化前缀，用于识别输出中是否泄漏了服务器路径。
     */
    private static final String NORMALIZED_ROOT = AppConstant.CODE_OUTPUT_ROOT_DIR.replace('\\', '/');

    private CodeOutputGuard() {
    }

    /**
     * 校验 HTML 模式的输出。
     *
     * @param result 模型解析结果
     * @throws BusinessException 输出不完整或泄漏服务器路径
     */
    public static void validate(HtmlCodeResult result) {
        if (result == null) {
            throw invalid("模型没有返回代码结果");
        }
        String html = result.getHtmlCode();
        checkHtml(html);
        checkNoServerPath("htmlCode", html);
    }

    /**
     * 校验多文件模式的输出。
     *
     * @param result 模型解析结果
     * @throws BusinessException 输出不完整或泄漏服务器路径
     */
    public static void validate(MultiFileCodeResult result) {
        if (result == null) {
            throw invalid("模型没有返回代码结果");
        }
        checkHtml(result.getHtmlCode());
        // CSS / JS 允许为空：纯静态展示页面确实可能不需要脚本。
        checkNoServerPath("htmlCode", result.getHtmlCode());
        checkNoServerPath("cssCode", result.getCssCode());
        checkNoServerPath("jsCode", result.getJsCode());
    }

    /**
     * HTML 必须像一份完整的页面：包含 html 标签或 DOCTYPE，且长度达到下限。
     */
    private static void checkHtml(String html) {
        if (html == null || html.isBlank()) {
            throw invalid("模型返回的 HTML 内容为空");
        }
        String normalized = html.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean looksLikeHtml = lower.startsWith("<!doctype")
                || lower.contains("<html")
                || (lower.contains("<body") && lower.contains("</body>"));
        if (!looksLikeHtml || normalized.length() < MIN_HTML_LENGTH) {
            throw invalid("模型返回的 HTML 不像是完整页面，可能被输出长度上限截断");
        }
    }

    /**
     * 输出中不允许出现服务器存储目录：这类路径对用户无意义，还会泄露部署结构。
     */
    private static void checkNoServerPath(String field, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        String normalized = content.replace('\\', '/');
        if (NORMALIZED_ROOT.length() > 8 && normalized.contains(NORMALIZED_ROOT)) {
            throw invalid("模型输出中的 " + field + " 包含服务器文件路径，已拒绝保存");
        }
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, detail + "，请重试");
    }
}
