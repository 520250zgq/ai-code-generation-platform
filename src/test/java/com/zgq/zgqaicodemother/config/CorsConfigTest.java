package com.zgq.zgqaicodemother.config;

import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.controller.StaticResourceController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercise the real MVC CORS configuration without loading database or AI services.
 */
@SpringJUnitWebConfig(CorsConfigTest.MvcConfiguration.class)
class CorsConfigTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";
    private static final String HTML = "<!doctype html><h1>应用预览</h1>";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private Path outputRoot;
    private Path appDirectory;
    private String previewUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        appDirectory = Files.createTempDirectory(outputRoot, "multi_file_cors_test_");
        Files.writeString(appDirectory.resolve("index.html"), HTML, StandardCharsets.UTF_8);
        previewUrl = "/api/static/" + appDirectory.getFileName() + "/";
    }

    @AfterEach
    void removeOnlyTestDirectory() throws IOException {
        if (appDirectory == null) {
            return;
        }
        assertEquals(outputRoot, appDirectory.getParent());
        try (Stream<Path> files = Files.walk(appDirectory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void crossOriginHeadCanProbeExistingPreview() throws Exception {
        mockMvc.perform(head(previewUrl).contextPath("/api")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH,
                        HTML.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void crossOriginGetStillServesPreviewHtml() throws Exception {
        mockMvc.perform(get(previewUrl).contextPath("/api")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(content().string(HTML));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void optionsPreflightAllowsPreviewRequests(String method) throws Exception {
        mockMvc.perform(options(previewUrl).contextPath("/api")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString(method)))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type"));
    }

    @Test
    void crossOriginHeadReturnsReadable404ForMissingPreview() throws Exception {
        String missingPreviewUrl = "/api/static/" + appDirectory.getFileName() + "_missing/";

        mockMvc.perform(head(missingPreviewUrl).contextPath("/api")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({CorsConfig.class, StaticResourceController.class})
    static class MvcConfiguration {
    }
}
