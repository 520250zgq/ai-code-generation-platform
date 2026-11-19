package com.zgq.zgqaicodemother.ai.model;

import dev.langchain4j.model.output.structured.Description;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 多文件生成结果，接收 HTML、CSS、JavaScript 及生成说明。
 */
@Description("生成多个代码文件的结果")
@Data
public class MultiFileCodeResult {

    @Description("HTML代码")
    private String htmlCode;

    @Description("CSS代码")
    private String cssCode;

    @Description("JS代码")
    private String jsCode;

    @Description("生成代码的描述")
    private String description;

    /**
     * JSON 反序列化入口，强制校验四个代码与说明字段均非空。
     */
    @JsonCreator
    public MultiFileCodeResult(@JsonProperty("htmlCode") String htmlCode,
                               @JsonProperty("cssCode") String cssCode,
                               @JsonProperty("jsCode") String jsCode,
                               @JsonProperty("description") String description) {
        if (htmlCode == null || htmlCode.isBlank() || cssCode == null || cssCode.isBlank()
                || jsCode == null || jsCode.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("AI 响应必须包含非空的 htmlCode、cssCode、jsCode 和 description");
        }
        this.htmlCode = htmlCode;
        this.cssCode = cssCode;
        this.jsCode = jsCode;
        this.description = description;
    }

    /**
     * 供框架反射和旧版 Markdown 代码块解析使用的无参构造。
     */
    public MultiFileCodeResult() {

    }
}
