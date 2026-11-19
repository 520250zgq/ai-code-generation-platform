package com.zgq.zgqaicodemother.utils;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.support.HangingHttpResource;
import com.zgq.zgqaicodemother.support.LocalHtmlServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 网页截图工具测试。
 *
 * <p>保存、压缩和等待逻辑使用确定性数据；真实截图测试要求本机存在可用的 Chrome 驱动，
 * 没有浏览器或驱动时自动跳过，不影响其余单元测试。</p>
 */
class WebScreenshotUtilsTest {

    private static final String RED_PAGE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
                <meta charset="utf-8">
                <style>
                    html, body { margin: 0; height: 100%; background: #d32f2f; }
                </style>
            </head>
            <body><h1>网页截图测试</h1></body>
            </html>
            """;

    @TempDir
    private Path tempDir;

    @Test
    void saveImageWritesBytesAndCreatesParentDirectories() {
        byte[] content = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);
        String path = tempDir.resolve("nested/dir/cover.png").toString();

        String savedPath = WebScreenshotUtils.saveImage(content, path);

        File savedFile = new File(savedPath);
        assertTrue(savedFile.isFile());
        assertEquals(content.length, savedFile.length());
        assertEquals("fake-image-bytes", FileUtil.readString(savedFile, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsEmptyImageContentAndBlankPath() {
        assertThrows(BusinessException.class, () -> WebScreenshotUtils.saveImage(new byte[0], "x.png"));
        assertThrows(BusinessException.class,
                () -> WebScreenshotUtils.saveImage(new byte[]{1}, " "));
    }

    @Test
    void compressImageProducesJpegWithSameDimensions() throws Exception {
        Path source = tempDir.resolve("source.png");
        Path target = tempDir.resolve("compressed/cover.jpg");
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(33, 150, 243));
            graphics.fillRect(0, 0, 320, 180);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(20, 20, 120, 40);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", source.toFile());

        String compressedPath = WebScreenshotUtils.compressImage(source.toString(), target.toString());

        File compressedFile = new File(compressedPath);
        assertTrue(compressedFile.isFile());
        assertEquals("jpg", FileUtil.extName(compressedFile));
        BufferedImage compressed = ImageIO.read(compressedFile);
        assertNotNull(compressed);
        assertEquals(320, compressed.getWidth());
        assertEquals(180, compressed.getHeight());
        assertTrue(compressedFile.length() > 0);
    }

    @Test
    void rejectsMissingSourceImage() {
        assertThrows(BusinessException.class, () -> WebScreenshotUtils.compressImage(
                tempDir.resolve("missing.png").toString(), tempDir.resolve("target.jpg").toString()));
    }

    @Test
    void waitForPageLoadWaitsUntilReadyStateAndBodyAreComplete() {
        WebDriver driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class));
        JavascriptExecutor javascriptExecutor = (JavascriptExecutor) driver;
        when(javascriptExecutor.executeScript("return document.readyState")).thenReturn("complete");
        when(javascriptExecutor.executeScript("return document.body !== null")).thenReturn(Boolean.TRUE);

        assertDoesNotThrow(() -> WebScreenshotUtils.waitForPageLoad(
                driver, Duration.ofSeconds(2), Duration.ZERO));

        verify(javascriptExecutor, atLeastOnce()).executeScript("return document.readyState");
        verify(javascriptExecutor, atLeastOnce()).executeScript("return document.body !== null");
    }

    @Test
    void waitForPageLoadTimesOutWhenPageNeverCompletes() {
        WebDriver driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class));
        JavascriptExecutor javascriptExecutor = (JavascriptExecutor) driver;
        when(javascriptExecutor.executeScript("return document.readyState")).thenReturn("loading");

        assertThrows(TimeoutException.class, () -> WebScreenshotUtils.waitForPageLoad(
                driver, Duration.ofMillis(100), Duration.ZERO));
    }

    @Test
    void screenshotRejectsBlankUrl() {
        assertThrows(BusinessException.class, () -> WebScreenshotUtils.screenshot("  "));
    }

    @Test
    void screenshotSucceedsWhenSubresourceNeverFinishesLoading() throws Exception {
        assumeTrue(WebScreenshotUtils.isDriverAvailable(),
                "本机没有可用的 Chrome 驱动，跳过真实截图测试");
        String screenshotPath = null;
        try (HangingHttpResource hangingResource = HangingHttpResource.start()) {
            String html = """
                    <!doctype html>
                    <html lang="zh-CN">
                    <head><meta charset="utf-8"><style>
                        html, body { margin: 0; height: 100%; background: #1976d2; }
                    </style></head>
                    <body><img src="__SLOW_IMAGE__" alt="永远不会加载完成的图片"></body>
                    </html>
                    """.replace("__SLOW_IMAGE__", hangingResource.url());
            try (LocalHtmlServer server = LocalHtmlServer.start(html)) {
                // 旧实现会等待 load 事件并卡满 30 秒；修复后应在资源等待上限内完成。
                screenshotPath = assertTimeoutPreemptively(Duration.ofSeconds(12),
                        () -> WebScreenshotUtils.screenshot(server.url()));
                assertNotNull(ImageIO.read(new File(screenshotPath)));
            }
        } finally {
            if (screenshotPath != null) {
                FileUtil.del(screenshotPath);
            }
        }
    }

    @Test
    void screenshotRendersPageAsCompressedJpeg() throws Exception {
        assumeTrue(WebScreenshotUtils.isDriverAvailable(),
                "本机没有可用的 Chrome 驱动，跳过真实截图测试");
        String screenshotPath = null;
        try (LocalHtmlServer server = LocalHtmlServer.start(RED_PAGE)) {
            screenshotPath = WebScreenshotUtils.screenshot(server.url());

            File screenshotFile = new File(screenshotPath);
            assertTrue(screenshotFile.isFile());
            assertEquals("jpg", FileUtil.extName(screenshotFile).toLowerCase());
            BufferedImage image = ImageIO.read(screenshotFile);
            assertNotNull(image);
            assertEquals(1280, image.getWidth());
            assertEquals(720, image.getHeight());
            Color pixel = new Color(image.getRGB(20, 20));
            assertTrue(pixel.getRed() > 150, "页面背景应渲染为红色：" + pixel);
            assertTrue(pixel.getGreen() < 120, "页面背景应渲染为红色：" + pixel);
            // 截图应位于统一的临时目录下，且 PNG 中间文件已被清理。
            assertTrue(screenshotFile.getAbsolutePath()
                    .startsWith(new File(com.zgq.zgqaicodemother.constant.AppConstant.SCREENSHOT_ROOT_DIR)
                            .getAbsolutePath()));
        } finally {
            if (screenshotPath != null) {
                FileUtil.del(screenshotPath);
            }
        }
    }
}
