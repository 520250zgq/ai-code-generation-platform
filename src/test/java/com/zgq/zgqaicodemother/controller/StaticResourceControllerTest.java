package com.zgq.zgqaicodemother.controller;

import com.zgq.zgqaicodemother.constant.AppConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Static preview HTTP and filesystem boundary regressions without Spring, a database, or AI.
 */
class StaticResourceControllerTest {

    private final StaticResourceController controller = new StaticResourceController();
    private final List<Path> temporaryDirectories = new ArrayList<>();
    private MockMvc mockMvc;
    private Path outputRoot;
    private Path appDirectory;
    private String appId;

    @BeforeEach
    void setUp() throws IOException {
        outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        appDirectory = createApplicationDirectory();
        appId = appDirectory.getFileName().toString();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void removeOnlyTestDirectories() throws IOException {
        for (Path directory : temporaryDirectories) {
            // Only remove directories returned by this test's createTempDirectory calls.
            assertEquals(outputRoot, directory.getParent());
            if (!Files.exists(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void vueProjectServesBuiltDistInsteadOfSourceEntry() throws Exception {
        Path vueDirectory = createVueApplicationDirectory();
        String vueId = vueDirectory.getFileName().toString();
        String builtHtml = "<!doctype html><html><body>构建后的 Vue 页面</body></html>";
        writeFile(vueDirectory, "index.html", "source entry");
        writeFile(vueDirectory, "dist/index.html", builtHtml);
        writeFile(vueDirectory, "dist/assets/app.js", "console.log('built')");

        mockMvc.perform(get("/static/" + vueId + "/"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)))
                .andExpect(content().string(builtHtml));
        mockMvc.perform(get("/static/" + vueId + "/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string("console.log('built')"));
    }

    @Test
    void vueProjectBeforeDistShowsAutoRefreshingBuildingPage() throws Exception {
        Path vueDirectory = createVueApplicationDirectory();
        String vueId = vueDirectory.getFileName().toString();
        writeFile(vueDirectory, "index.html", "source entry");

        mockMvc.perform(get("/static/" + vueId + "/"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)))
                .andExpect(content().string(containsString("Vue 项目正在构建")))
                .andExpect(content().string(containsString("http-equiv=\"refresh\"")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        // dist 还不存在时，源码入口和资源都不应该被浏览器直接执行。
        mockMvc.perform(get("/static/" + vueId + "/src/main.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void applicationRootServesUtf8Index() throws Exception {
        String html = "<!doctype html><html><body>生成后即可预览 🚀</body></html>";
        writeFile("index.html", html);

        mockMvc.perform(get(previewUrl("/")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)))
                .andExpect(content().string(html))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void multiFilePreviewServesCssAndNestedJavascript() throws Exception {
        String css = "body::before { content: '欢迎'; }";
        String javascript = "document.title = '预览';";
        writeFile("style.css", css);
        writeFile("assets/js/script.js", javascript);

        mockMvc.perform(get(previewUrl("/style.css")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType("text", "css", StandardCharsets.UTF_8)))
                .andExpect(content().string(css));
        mockMvc.perform(get(previewUrl("/assets/js/script.js")))
                .andExpect(status().isOk())
                .andExpect(content().encoding(StandardCharsets.UTF_8))
                .andExpect(content().string(javascript));
    }

    @Test
    void nestedDirectoryServesItsOwnIndex() throws Exception {
        writeFile("docs/index.html", "<h1>嵌套页面</h1>");

        mockMvc.perform(get(previewUrl("/docs/")))
                .andExpect(status().isOk())
                .andExpect(content().string("<h1>嵌套页面</h1>"));
    }

    @Test
    void bareApplicationUrlRedirectsWithApiContextAndTrailingSlash() throws Exception {
        mockMvc.perform(get("/api" + previewUrl("")).contextPath("/api"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api" + previewUrl("/")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void missingApplicationReturns404() throws Exception {
        // A child path under this isolated application is guaranteed not to be a root application.
        String missingApplication = appId + "_does_not_exist";

        mockMvc.perform(get("/static/" + missingApplication + "/"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void missingResourceAndMissingIndexReturn404() throws Exception {
        mockMvc.perform(get(previewUrl("/missing.css")))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(get(previewUrl("/")))
                .andExpect(status().isNotFound());
    }

    @Test
    void regeneratedPreviewReadsNewFileContentsWithoutCaching() throws Exception {
        writeFile("index.html", "first version");
        mockMvc.perform(get(previewUrl("/")))
                .andExpect(status().isOk())
                .andExpect(content().string("first version"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        writeFile("index.html", "重新生成后的最新内容");

        mockMvc.perform(get(previewUrl("/")))
                .andExpect(status().isOk())
                .andExpect(content().string("重新生成后的最新内容"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void parentTraversalCannotReadAnotherApplicationsFile() throws Exception {
        Path otherApplication = createApplicationDirectory();
        Files.writeString(otherApplication.resolve("index.html"), "another application's private content");

        ResponseEntity<Resource> response = controller.serveStaticResource(appId,
                "/../" + otherApplication.getFileName() + "/index.html", request());

        assertNotFound(response);
    }

    @Test
    void encodedParentTraversalCannotReadAnotherApplicationsFile() throws Exception {
        Path otherApplication = createApplicationDirectory();
        Files.writeString(otherApplication.resolve("index.html"), "another application's private content");
        URI encodedUrl = URI.create(previewUrl("/%2e%2e/" + otherApplication.getFileName() + "/index.html"));

        mockMvc.perform(get(encodedUrl))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void windowsBackslashTraversalIsRejected() throws Exception {
        Path otherApplication = createApplicationDirectory();
        Files.writeString(otherApplication.resolve("index.html"), "another application's private content");

        ResponseEntity<Resource> response = controller.serveStaticResource(appId,
                "/..\\" + otherApplication.getFileName() + "\\index.html", request());

        assertNotFound(response);
    }

    @Test
    void applicationDirectoryCannotContainPathSegments() throws Exception {
        assertNotFound(controller.serveStaticResource("../" + appId, "/index.html", request()));
        assertNotFound(controller.serveStaticResource("..\\" + appId, "/index.html", request()));
    }

    @Test
    void unknownBinaryResourceUsesOctetStreamWithoutChangingBytes() throws Exception {
        byte[] bytes = {0, 1, 2, (byte) 0xff};
        Files.write(appDirectory.resolve("asset.unknownpreviewtype"), bytes);

        mockMvc.perform(get(previewUrl("/asset.unknownpreviewtype")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(bytes));
    }

    private Path createApplicationDirectory() throws IOException {
        Path directory = Files.createTempDirectory(outputRoot, "multi_file_preview_test_");
        temporaryDirectories.add(directory);
        return directory;
    }

    private Path createVueApplicationDirectory() throws IOException {
        Path directory = Files.createTempDirectory(outputRoot, "vue_project_preview_test_");
        temporaryDirectories.add(directory);
        return directory;
    }

    private void writeFile(String relativePath, String content) throws IOException {
        writeFile(appDirectory, relativePath, content);
    }

    private void writeFile(Path directory, String relativePath, String content) throws IOException {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String previewUrl(String resourcePath) {
        return "/static/" + appId + resourcePath;
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", previewUrl("/"));
    }

    private void assertNotFound(ResponseEntity<Resource> response) {
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
}
