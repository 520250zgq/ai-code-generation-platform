package com.zgq.zgqaicodemother.controller;

import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 提供生成代码的静态预览，目录名与保存器的 {代码类型}_{应用 id} 保持一致。
 */
@RestController
@RequestMapping("/static")
public class StaticResourceController {

    // 应用生成根目录（用于预览），生成完成即可访问，无需先部署。
    private static final String PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    /**
     * Vue 工程构建完成前返回的提示页。页面会自动刷新，构建出 dist/index.html 后即可进入正式页面。
     */
    private static final String VUE_BUILDING_PAGE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <meta http-equiv="refresh" content="3">
              <title>Vue 项目构建中</title>
              <style>
                body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                       font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                       background: #f7f8fa; color: #333; }
                .card { padding: 32px 40px; border-radius: 12px; background: #fff;
                        box-shadow: 0 8px 30px rgba(0, 0, 0, .08); text-align: center; }
                p { margin: 8px 0 0; color: #888; font-size: 14px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h2>Vue 项目正在构建</h2>
                <p>正在安装依赖并打包，请稍候，页面会自动刷新。</p>
              </div>
            </body>
            </html>
            """;

    /**
     * 访问应用目录时返回 index.html，其他路径返回对应的 CSS、JavaScript 等资源。
     *
     * <p>Vue 工程构建完成后以 {@code dist} 作为可浏览目录；dist 尚未生成时返回自动刷新的
     * 构建提示页，避免用户看到未经过 Vite 处理的源码入口。</p>
     */
    @GetMapping({"/{appId}", "/{appId}/{*resourcePath}"})
    public ResponseEntity<Resource> serveStaticResource(
            @PathVariable("appId") String appId,
            @PathVariable(value = "resourcePath", required = false) String resourcePath,
            HttpServletRequest request) throws IOException {
        // 限制应用目录为单个路径段，兼容 html_123、multi_file_123、vue_project_123 等生成目录。
        if (!appId.matches("[A-Za-z0-9_-]+")) {
            return notFound();
        }
        Path root = Path.of(PREVIEW_ROOT_DIR).toAbsolutePath().normalize();
        Path appDirectory = root.resolve(appId);
        if (!Files.isDirectory(appDirectory)) {
            return notFound();
        }
        boolean vueProject = appId.startsWith(CodeGenTypeEnum.VUE_PROJECT.getValue() + "_");
        Path distDirectory = appDirectory.resolve("dist");
        boolean distReady = vueProject && Files.isDirectory(distDirectory);
        // Vue 源码入口不能直接给浏览器执行，构建完成后改为从 dist 提供预览。
        Path servingDirectory = distReady ? distDirectory : appDirectory;
        Path realAppDirectory = servingDirectory.toRealPath();
        if (!realAppDirectory.startsWith(root.toRealPath())) {
            return notFound();
        }

        // 保留目录末尾的 /，让 HTML 中的相对资源地址正确落在应用目录下。
        if (resourcePath == null || resourcePath.isEmpty()) {
            URI location = URI.create(request.getContextPath() + "/static/"
                    + UriUtils.encodePathSegment(appId, StandardCharsets.UTF_8) + "/");
            return ResponseEntity.status(HttpStatus.FOUND).location(location)
                    .cacheControl(CacheControl.noStore()).build();
        }
        String relativePath = resourcePath.substring(1);
        if (relativePath.contains("\\") || relativePath.contains(":")) {
            return notFound();
        }
        if (vueProject && !distReady) {
            // 构建完成前只对入口返回提示页，静态资源仍按 404 处理。
            if (relativePath.isEmpty() || relativePath.endsWith("/") || "index.html".equals(relativePath)) {
                return buildingPage();
            }
            return notFound();
        }
        if (relativePath.isEmpty() || relativePath.endsWith("/")) {
            relativePath += "index.html";
        }
        Path file;
        try {
            file = servingDirectory.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            return notFound();
        }
        // 同时检查规范路径与真实路径，防止 ../ 或符号链接读取应用目录之外的文件。
        if (!file.startsWith(servingDirectory) || !Files.isRegularFile(file) || !Files.isReadable(file)
                || !file.toRealPath().startsWith(realAppDirectory)) {
            return notFound();
        }

        Resource resource = new FileSystemResource(file);
        MediaType contentType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        if ("text".equals(contentType.getType()) || MediaType.APPLICATION_JSON.includes(contentType)
                || "javascript".equals(contentType.getSubtype())) {
            contentType = new MediaType(contentType, StandardCharsets.UTF_8);
        }
        // 同一应用再次生成时文件会被覆盖，预览请求始终读取最新内容。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(contentType).body(resource);
    }

    /**
     * 返回 Vue 构建中的提示页，构建完成后页面会自动刷新到 dist/index.html。
     */
    private ResponseEntity<Resource> buildingPage() {
        byte[] content = VUE_BUILDING_PAGE.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(new ByteArrayResource(content));
    }

    /**
     * 构造不缓存的 404 响应，避免无效预览路径被浏览器或代理缓存。
     */
    private ResponseEntity<Resource> notFound() {
        return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
    }
}
