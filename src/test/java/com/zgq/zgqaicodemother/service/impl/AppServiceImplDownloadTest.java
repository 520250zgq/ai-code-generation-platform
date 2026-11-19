package com.zgq.zgqaicodemother.service.impl;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.constant.UserConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 应用代码下载的权限、目录选择和 HTTP 输出测试，不启动 Spring 容器。
 */
class AppServiceImplDownloadTest {

    private static final long OWNER_ID = 7L;

    private AppServiceImpl appService;
    private long appId;
    private Path sourceDir;

    @BeforeEach
    void setUp() {
        // 使用真实雪花 id 之外的独立区间，避免覆盖本机已生成的应用目录。
        appId = 9_100_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        appService = spy(new AppServiceImpl());
        sourceDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                CodeGenTypeEnum.MULTI_FILE.getValue() + "_" + appId);
    }

    @AfterEach
    void cleanup() {
        FileUtil.del(sourceDir.toFile());
    }

    @Test
    void ownerReceivesFilteredZipWithDownloadHeaders() throws IOException {
        doReturn(appOwnedBy(OWNER_ID)).when(appService).getById(appId);
        writeFile("index.html", "<h1>我的作品</h1>");
        writeFile("src/main.js", "console.log('app')");
        writeFile("package.json", "{}");
        writeFile("node_modules/vue/index.js", "dependency");
        writeFile("dist/index.html", "built");
        writeFile("build/app.js", "built");
        writeFile(".env", "SECRET=1");
        writeFile(".env.local", "SECRET=2");
        writeFile(".DS_Store", "metadata");
        writeFile("logs/app.log", "log");
        writeFile(".git/config", "git");
        MockHttpServletResponse response = new MockHttpServletResponse();

        appService.downloadAppCode(appId, loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response);

        assertEquals(200, response.getStatus());
        assertEquals("application/zip", response.getContentType());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"), disposition);
        String decodedName = decodeFilenameStar(disposition);
        assertEquals("我的作品_" + appId + ".zip", decodedName);

        Map<String, String> entries = readZip(response.getContentAsByteArray());
        assertEquals(List.of("index.html", "package.json", "src/main.js"),
                entries.keySet().stream().toList());
        assertEquals("<h1>我的作品</h1>", entries.get("index.html"));
        assertEquals("console.log('app')", entries.get("src/main.js"));
        assertEquals("{}", entries.get("package.json"));
        entries.keySet().forEach(entry -> {
            assertFalse(entry.startsWith("/"), entry);
            assertFalse(entry.contains("\\"), entry);
            assertFalse(entry.contains(".."), entry);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {UserConstant.DEFAULT_ROLE, UserConstant.ADMIN_ROLE})
    void onlyTheCreatorCanDownload(String role) {
        doReturn(appOwnedBy(OWNER_ID + 1)).when(appService).getById(appId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(appId, loginUser(OWNER_ID, role), response));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
        assertNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void missingApplicationIsRejected() {
        doReturn(null).when(appService).getById(appId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(appId, loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), error.getCode());
        assertNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void missingGeneratedDirectoryIsRejected() {
        doReturn(appOwnedBy(OWNER_ID)).when(appService).getById(appId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(appId, loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), error.getCode());
        assertNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void directoryWithOnlyExcludedFilesIsRejectedBeforeWritingZip() throws IOException {
        doReturn(appOwnedBy(OWNER_ID)).when(appService).getById(appId);
        writeFile("node_modules/vue/index.js", "dependency");
        writeFile(".env", "SECRET=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(appId, loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), error.getCode());
        assertNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void unsupportedCodeGenTypeIsRejected() {
        App app = appOwnedBy(OWNER_ID);
        app.setCodeGenType("unknown_type");
        doReturn(app).when(appService).getById(appId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(appId, loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), error.getCode());
        assertNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAppIdIsRejectedBeforeQuerying(Long invalidAppId) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(invalidAppId,
                        loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        verify(appService, never()).getById(any());
    }

    @Test
    void missingLoginIsRejected() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        BusinessException error = assertThrows(BusinessException.class,
                () -> appService.downloadAppCode(appId, null, response));

        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), error.getCode());
        verify(appService, never()).getById(any());
    }

    @Test
    void unsafeAppNameCannotBreakDownloadHeader() throws IOException {
        App app = appOwnedBy(OWNER_ID);
        app.setAppName("a\"b\r\nc/d");
        doReturn(app).when(appService).getById(appId);
        writeFile("index.html", "ok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        appService.downloadAppCode(appId, loginUser(OWNER_ID, UserConstant.DEFAULT_ROLE), response);

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"), disposition);
        assertFalse(disposition.contains("\r"), disposition);
        assertFalse(disposition.contains("\n"), disposition);
        assertEquals("a_b__c_d_" + appId + ".zip", decodeFilenameStar(disposition));
    }

    private App appOwnedBy(long userId) {
        return App.builder()
                .id(appId)
                .appName("我的作品")
                .userId(userId)
                .codeGenType(CodeGenTypeEnum.MULTI_FILE.getValue())
                .build();
    }

    private static User loginUser(long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUserRole(role);
        return user;
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = sourceDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String decodeFilenameStar(String contentDisposition) {
        String marker = "filename*=UTF-8''";
        int start = contentDisposition.indexOf(marker);
        assertTrue(start >= 0, contentDisposition);
        String encoded = contentDisposition.substring(start + marker.length());
        int parameterEnd = encoded.indexOf(';');
        if (parameterEnd >= 0) {
            encoded = encoded.substring(0, parameterEnd);
        }
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static Map<String, String> readZip(byte[] zipBytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zipInput = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zipInput.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
