package com.zgq.zgqaicodemother.workflow.http;

import cn.hutool.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 工作流使用的最小 HTTP 客户端。
 *
 * <p>只暴露图片收集需要的四种能力：读取 JSON、读取文本、提交 JSON、下载图片。抽成接口是为了
 * 让工具层可以在单元测试中注入假实现，不依赖真实网络；生产实现统一处理超时、响应大小上限、
 * 重定向和域名白名单。</p>
 */
public interface HttpJsonClient {

    /**
     * 发送 GET 请求并解析 JSON 对象。
     *
     * @param uri      请求地址，只允许 https
     * @param headers  请求头，不能包含 CR/LF
     * @param timeout  请求超时
     * @param maxBytes 响应体最大字节数
     * @return 解析后的 JSON 对象
     * @throws IOException 网络、状态码或 JSON 解析失败
     */
    JSONObject getJson(URI uri, Map<String, String> headers, Duration timeout, int maxBytes) throws IOException;

    /**
     * 发送 POST JSON 请求并解析响应 JSON 对象。
     *
     * @param uri      请求地址，只允许 https
     * @param headers  请求头
     * @param jsonBody 请求体，会被原样发送，调用方负责保证是合法 JSON
     * @param timeout  请求超时
     * @param maxBytes 响应体最大字节数
     * @return 解析后的 JSON 对象
     * @throws IOException 网络、状态码或 JSON 解析失败
     */
    JSONObject postJson(URI uri, Map<String, String> headers, String jsonBody,
                        Duration timeout, int maxBytes) throws IOException;

    /**
     * 发送 GET 请求并按 UTF-8 返回文本，用于解析 HTML 中内嵌的 JSON。
     *
     * @param uri      请求地址，只允许 https
     * @param headers  请求头
     * @param timeout  请求超时
     * @param maxBytes 响应体最大字节数
     * @return 响应文本
     * @throws IOException 网络或状态码失败
     */
    String getText(URI uri, Map<String, String> headers, Duration timeout, int maxBytes) throws IOException;

    /**
     * 下载图片。只接受白名单域名和 image/* 响应，自动处理有限次重定向。
     *
     * @param uri                 图片地址
     * @param allowedHostSuffixes 允许的域名后缀，例如 aliyuncs.com
     * @param timeout             请求超时
     * @param maxBytes            图片最大字节数
     * @return 下载结果
     * @throws IOException 网络、状态码、内容类型或域名校验失败
     */
    DownloadedResource download(URI uri, Set<String> allowedHostSuffixes,
                                Duration timeout, int maxBytes) throws IOException;
}
