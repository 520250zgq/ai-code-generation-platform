package com.zgq.zgqaicodemother.service.impl;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.support.LocalHtmlServer;
import com.zgq.zgqaicodemother.utils.WebScreenshotUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通用截图服务测试：验证 URL 校验、本地截图、上传和临时文件清理。
 */
@ExtendWith(MockitoExtension.class)
class ScreenshotServiceImplTest {

    private static final String RED_PAGE = """
            <!doctype html>
            <html lang="zh-CN">
            <head><meta charset="utf-8"><style>
                html, body { margin: 0; height: 100%; background: #1976d2; }
            </style></head>
            <body><h1>截图服务测试</h1></body>
            </html>
            """;

    @Mock
    private CosManager cosManager;

    @InjectMocks
    private ScreenshotServiceImpl screenshotService;

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ftp://example.com", "not-a-url", "http://"})
    void rejectsInvalidUrlBeforeScreenshot(String webUrl) {
        assertThrows(BusinessException.class,
                () -> screenshotService.generateAndUploadScreenshot(webUrl));
        verifyNoInteractions(cosManager);
    }

    @Test
    void generatesScreenshotUploadsItAndDeletesLocalFile() throws Exception {
        assumeTrue(WebScreenshotUtils.isDriverAvailable(),
                "本机没有可用的 Chrome 驱动，跳过真实截图服务测试");
        when(cosManager.uploadImage(anyString())).thenReturn("https://cdn.example.com/cover.jpg");

        try (LocalHtmlServer server = LocalHtmlServer.start(RED_PAGE)) {
            String imageUrl = screenshotService.generateAndUploadScreenshot(server.url());

            assertEquals("https://cdn.example.com/cover.jpg", imageUrl);
            ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
            verify(cosManager).uploadImage(pathCaptor.capture());
            File localFile = new File(pathCaptor.getValue());
            assertFalse(localFile.exists(), "上传成功后应删除本地截图文件");
            assertTrue(pathCaptor.getValue().endsWith(".jpg"));
        }
    }

    @Test
    void deletesLocalFileWhenUploadFails() throws Exception {
        assumeTrue(WebScreenshotUtils.isDriverAvailable(),
                "本机没有可用的 Chrome 驱动，跳过真实截图服务测试");
        when(cosManager.uploadImage(anyString()))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "模拟上传失败"));

        try (LocalHtmlServer server = LocalHtmlServer.start(RED_PAGE)) {
            assertThrows(BusinessException.class,
                    () -> screenshotService.generateAndUploadScreenshot(server.url()));

            ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
            verify(cosManager).uploadImage(pathCaptor.capture());
            assertFalse(new File(pathCaptor.getValue()).exists(),
                    "上传失败后也应删除本地截图文件");
        }
    }

    @Test
    void rejectsBlankUploadResult() throws Exception {
        assumeTrue(WebScreenshotUtils.isDriverAvailable(),
                "本机没有可用的 Chrome 驱动，跳过真实截图服务测试");
        when(cosManager.uploadImage(anyString())).thenReturn("  ");

        try (LocalHtmlServer server = LocalHtmlServer.start(RED_PAGE)) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> screenshotService.generateAndUploadScreenshot(server.url()));
            assertEquals(ErrorCode.OPERATION_ERROR.getCode(), error.getCode());
        }
    }
}
