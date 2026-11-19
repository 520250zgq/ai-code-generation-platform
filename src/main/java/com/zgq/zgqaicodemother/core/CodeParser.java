package com.zgq.zgqaicodemother.core;

import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.core.parser.HtmlCodeParser;
import com.zgq.zgqaicodemother.core.parser.MultiFileCodeParser;

/**
 * 兼容旧静态入口，复用流式生成使用的代码解析器。
 *
 * @author yupi
 */
public class CodeParser {

    private static final HtmlCodeParser HTML_CODE_PARSER = new HtmlCodeParser();
    private static final MultiFileCodeParser MULTI_FILE_CODE_PARSER = new MultiFileCodeParser();

    /**
     * 解析 HTML 单文件代码，兼容 JSON 与 Markdown 代码块。
     */
    public static HtmlCodeResult parseHtmlCode(String codeContent) {
        return HTML_CODE_PARSER.parseCode(codeContent);
    }

    /**
     * 解析多文件代码（HTML + CSS + JS）。
     */
    public static MultiFileCodeResult parseMultiFileCode(String codeContent) {
        return MULTI_FILE_CODE_PARSER.parseCode(codeContent);
    }
}
