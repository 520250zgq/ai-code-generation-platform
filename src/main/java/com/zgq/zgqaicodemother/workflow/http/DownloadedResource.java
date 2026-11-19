package com.zgq.zgqaicodemother.workflow.http;

import java.net.URI;

/**
 * 下载成功的图片资源。
 *
 * @param content     图片字节
 * @param contentType 响应 Content-Type，已校验为 image/*
 * @param finalUri    最终响应地址，可能经过重定向
 */
public record DownloadedResource(byte[] content, String contentType, URI finalUri) {
}
