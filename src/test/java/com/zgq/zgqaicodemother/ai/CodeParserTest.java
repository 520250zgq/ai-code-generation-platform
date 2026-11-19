package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.core.CodeParser;
import com.zgq.zgqaicodemother.core.parser.CodeParserExecutor;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeParserTest {

    private static final String HTML_JSON = """
            {"htmlCode":"<!DOCTYPE html>\\n<html lang=\\"zh\\"><body>任务记录</body></html>","description":"任务网站"}
            """;

    private static final String MULTI_FILE_JSON = """
            {
              "htmlCode": "<!DOCTYPE html>\\n<html><head><link href=\\"style.css\\" rel=\\"stylesheet\\"></head><body><script src=\\"script.js\\"></script></body></html>",
              "cssCode": "body {\\n  color: blue;\\n}",
              "jsCode": "const pattern = /\\\\d+/;\\nconsole.log(\\"ready\\");",
              "description": "多文件任务网站"
            }
            """;

    @Test
    void parseHtmlJsonAndPreserveEscapedCode() {
        HtmlCodeResult result = CodeParser.parseHtmlCode(HTML_JSON);

        assertEquals("<!DOCTYPE html>\n<html lang=\"zh\"><body>任务记录</body></html>", result.getHtmlCode());
        assertEquals("任务网站", result.getDescription());
        assertEquals(result, CodeParserExecutor.executeParser(HTML_JSON, CodeGenTypeEnum.HTML));
    }

    @Test
    void parseMultiFileJsonAndPreserveEscapedCode() {
        MultiFileCodeResult result = CodeParser.parseMultiFileCode(MULTI_FILE_JSON);

        assertAll(
                () -> assertEquals("<!DOCTYPE html>\n<html><head><link href=\"style.css\" rel=\"stylesheet\"></head><body><script src=\"script.js\"></script></body></html>", result.getHtmlCode()),
                () -> assertEquals("body {\n  color: blue;\n}", result.getCssCode()),
                () -> assertEquals("const pattern = /\\d+/;\nconsole.log(\"ready\");", result.getJsCode()),
                () -> assertEquals("多文件任务网站", result.getDescription())
        );
        assertEquals(result, CodeParserExecutor.executeParser(MULTI_FILE_JSON, CodeGenTypeEnum.MULTI_FILE));
    }

    @Test
    void acceptJsonInsideMarkdownFence() {
        assertEquals(CodeParser.parseHtmlCode(HTML_JSON),
                CodeParser.parseHtmlCode("```json\n" + HTML_JSON + "```"));
        assertEquals(CodeParser.parseMultiFileCode(MULTI_FILE_JSON),
                CodeParser.parseMultiFileCode("```JSON\r\n" + MULTI_FILE_JSON + "```"));
    }

    @Test
    void acceptLegacyHtmlFenceAndPlainHtml() {
        String html = "<!DOCTYPE html>\n<html><body>Hello World!</body></html>";

        assertEquals(html, CodeParser.parseHtmlCode("页面代码：\n```html\n" + html + "\n```\n完成").getHtmlCode());
        assertEquals(html, CodeParser.parseHtmlCode(html).getHtmlCode());
    }

    @Test
    void acceptLegacyMultiFileFences() {
        String content = """
                创建一个完整网页：
                ```html
                <html><head><link href="style.css" rel="stylesheet"></head><body><script src="script.js"></script></body></html>
                ```
                ```css
                h1 { color: blue; }
                ```
                ```javascript
                console.log('页面加载完成');
                ```
                """;

        MultiFileCodeResult result = CodeParser.parseMultiFileCode(content);
        assertAll(
                () -> assertTrue(result.getHtmlCode().startsWith("<html>")),
                () -> assertEquals("h1 { color: blue; }", result.getCssCode()),
                () -> assertEquals("console.log('页面加载完成');", result.getJsCode())
        );
    }

    @Test
    void rejectEmptyResponses() {
        for (String content : new String[]{null, "", " \n\t "}) {
            assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode(content));
            assertThrows(BusinessException.class, () -> CodeParser.parseMultiFileCode(content));
        }
    }

    @Test
    void rejectMalformedOrTruncatedJsonAndPreserveCause() {
        for (String content : new String[]{"{", "{\"htmlCode\":\"<html>\"", "{invalid}",
                HTML_JSON.strip() + " {}", "[]"}) {
            BusinessException htmlFailure = assertThrows(BusinessException.class,
                    () -> CodeParser.parseHtmlCode(content));
            BusinessException multiFileFailure = assertThrows(BusinessException.class,
                    () -> CodeParser.parseMultiFileCode(content));
            assertTrue(htmlFailure.getMessage().contains("JSON"));
            assertNotNull(htmlFailure.getCause());
            assertNotNull(multiFileFailure.getCause());
        }
    }

    @Test
    void rejectMissingOrBlankRequiredJsonFields() {
        for (String content : new String[]{"{}", "{\"htmlCode\":\"<html></html>\"}",
                "{\"htmlCode\":\" \",\"description\":\"网站\"}"}) {
            assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode(content));
        }
        for (String content : new String[]{HTML_JSON, "{}",
                MULTI_FILE_JSON.replace("\"cssCode\": \"body {\\n  color: blue;\\n}\"", "\"cssCode\": \"\""),
                MULTI_FILE_JSON.replace("\"description\": \"多文件任务网站\"", "\"description\": null")}) {
            assertThrows(BusinessException.class, () -> CodeParser.parseMultiFileCode(content));
        }
    }

    @Test
    void rejectIncompleteFencesAndUnexpectedText() {
        assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode("```json\n" + HTML_JSON));
        assertThrows(BusinessException.class, () -> CodeParser.parseMultiFileCode("```json\n" + MULTI_FILE_JSON));
        assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode("```json\nnull\n```"));
        assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode("```html\n<html>"));
        assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode("```html\n \n```"));
        assertThrows(BusinessException.class, () -> CodeParser.parseHtmlCode("模型生成失败，请重试"));
        assertThrows(BusinessException.class, () -> CodeParser.parseMultiFileCode("```css\nbody {}\n```"));
    }
}
