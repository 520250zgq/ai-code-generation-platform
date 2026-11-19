package com.zgq.zgqaicodemother.core.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the complete JSON response after all streaming fragments are collected.
 */
final class CodeJsonParser {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private static final Pattern JSON_CODE_PATTERN = Pattern.compile(
            "\\A```json[\\t ]*\\R([\\s\\S]*?)\\R?```\\s*\\z", Pattern.CASE_INSENSITIVE);

    private CodeJsonParser() {
    }

    /**
     * 校验并去除模型响应首尾空白，空白响应直接转为业务异常。
     */
    static String requireContent(String codeContent) {
        if (codeContent == null || codeContent.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型返回代码内容不能为空");
        }
        return codeContent.strip();
    }

    /**
     * 判断模型返回的是 JSON 对象还是 JSON 代码围栏，供解析器选择解析分支。
     */
    static boolean isJsonResponse(String content) {
        return content.startsWith("{") || content.startsWith("[")
                || content.regionMatches(true, 0, "```json", 0, 7);
    }

    /**
     * 把完整 JSON 或 ```json 围栏解析为指定结果类型，并校验必需字段。
     */
    static <T> T parse(String content, Class<T> resultType, String requiredFields) {
        String json = content;
        if (content.startsWith("```")) {
            Matcher matcher = JSON_CODE_PATTERN.matcher(content);
            if (!matcher.matches()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 响应的 JSON 代码围栏不完整或格式错误");
            }
            json = matcher.group(1);
        }
        try {
            // DTO @JsonCreator constructors validate required, nonblank fields.
            T result = OBJECT_MAPPER.readValue(json, resultType);
            if (result == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 响应 JSON 必须为非空代码结果对象");
            }
            return result;
        } catch (JsonProcessingException e) {
            BusinessException exception = new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 返回的 JSON 代码不完整或格式错误（可能达到输出长度上限），请重试；必须包含字段：" + requiredFields);
            exception.initCause(e);
            throw exception;
        }
    }
}
