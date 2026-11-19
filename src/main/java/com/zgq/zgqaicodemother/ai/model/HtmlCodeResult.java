package com.zgq.zgqaicodemother.ai.model;

import dev.langchain4j.model.output.structured.Description;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * HTML 单文件生成结果，用于接收并校验模型返回的 JSON。
 */
@Description("生成 HTML 代码文件的结果")
@Data
public class HtmlCodeResult {

    @Description("HTML代码")
    private String htmlCode;

    @Description("生成代码的描述")
    private String description;

    /**
     * JSON 反序列化入口，强制校验 HTML 和描述均非空。
     */
    @JsonCreator
    public HtmlCodeResult(@JsonProperty("htmlCode") String htmlCode,
                          @JsonProperty("description") String description) {
        if (htmlCode == null || htmlCode.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("AI 响应必须包含非空的 htmlCode 和 description");
        }
        this.htmlCode = htmlCode;
        this.description = description;
    }

    /**
     * 供框架反射和旧版 Markdown 代码块解析使用的无参构造。
     */
    public HtmlCodeResult() {

    }
}
