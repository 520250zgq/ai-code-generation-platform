package com.zgq.zgqaicodemother.core.parser;

import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码解析器（HTML + CSS + JS）
 *
 * @author yupi
 */
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html[\\t ]*\\R([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CODE_PATTERN = Pattern.compile("```css[\\t ]*\\R([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_CODE_PATTERN = Pattern.compile("```(?:js|javascript)[\\t ]*\\R([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * 优先解析 JSON 多文件结果，并兼容旧的 Markdown 代码块响应。
     */
    @Override
    public MultiFileCodeResult parseCode(String codeContent) {
        String content = CodeJsonParser.requireContent(codeContent);
        if (CodeJsonParser.isJsonResponse(content)) {
            return CodeJsonParser.parse(content, MultiFileCodeResult.class,
                    "htmlCode、cssCode、jsCode、description");
        }
        MultiFileCodeResult result = new MultiFileCodeResult();
        // 兼容旧的 Markdown 多文件代码块；至少必须包含非空 HTML。
        String htmlCode = extractCodeByPattern(content, HTML_CODE_PATTERN);
        String cssCode = extractCodeByPattern(content, CSS_CODE_PATTERN);
        String jsCode = extractCodeByPattern(content, JS_CODE_PATTERN);
        if (htmlCode == null || htmlCode.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "多文件代码响应缺少非空 HTML 代码块，请返回完整 JSON 或 Markdown 代码块");
        }
        // 设置HTML代码
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        }
        // 设置CSS代码
        if (cssCode != null && !cssCode.trim().isEmpty()) {
            result.setCssCode(cssCode.trim());
        }
        // 设置JS代码
        if (jsCode != null && !jsCode.trim().isEmpty()) {
            result.setJsCode(jsCode.trim());
        }
        return result;
    }

    /**
     * 根据正则模式提取代码
     *
     * @param content 原始内容
     * @param pattern 正则模式
     * @return 提取的代码
     */
    private String extractCodeByPattern(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
