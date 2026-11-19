package com.zgq.zgqaicodemother.core.parser;

import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 单文件代码解析器
 *
 * @author yupi
 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html[\\t ]*\\R([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * 兼容 JSON、Markdown HTML 代码块和纯 HTML 三种模型响应格式。
     */
    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        String content = CodeJsonParser.requireContent(codeContent);
        if (CodeJsonParser.isJsonResponse(content)) {
            return CodeJsonParser.parse(content, HtmlCodeResult.class, "htmlCode、description");
        }
        HtmlCodeResult result = new HtmlCodeResult();
        // 兼容旧的 Markdown HTML 代码块和纯 HTML 响应。
        String htmlCode = extractHtmlCode(content);
        if (htmlCode != null) {
            if (htmlCode.isBlank()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML代码内容不能为空");
            }
            result.setHtmlCode(htmlCode.strip());
        } else if (content.startsWith("<")) {
            result.setHtmlCode(content);
        } else {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 响应格式错误：请返回 HTML 结果 JSON、完整 HTML 代码块或纯 HTML");
        }
        return result;
    }

    /**
     * 提取HTML代码内容
     *
     * @param content 原始内容
     * @return HTML代码
     */
    private String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
