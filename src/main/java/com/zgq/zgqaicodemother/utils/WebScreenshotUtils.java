package com.zgq.zgqaicodemother.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 网页截图工具。
 *
 * <p>整个应用生命周期内只创建一个无头 ChromeDriver：静态代码块负责初始化，截图方法默认复用该实例，
 * 应用停止时通过 {@link #quit()} 释放浏览器进程。Selenium Manager 会自动匹配并下载与本机 Chrome
 * 版本一致的驱动，也可以通过 {@code -Dapp.screenshot.chrome-binary=...} 指定浏览器可执行文件。</p>
 *
 * <p>ChromeDriver 不是线程安全的，同一实例不能并发导航和截图，因此截图流程使用公平锁串行执行。
 * 页面加载采用 {@link PageLoadStrategy#EAGER}，只等待 DOMContentLoaded；图片、字体等子资源不再决定
 * 截图是否成功，避免第三方外链挂起时 {@code driver.get()} 一直等到页面加载超时。</p>
 *
 * <p>截图先保存为 PNG，再压缩成质量可配置的 JPEG，最终返回压缩文件的绝对路径；PNG 中间文件会被清理。</p>
 *
 * <p>可通过系统属性调整截图参数：{@code app.screenshot.width}、{@code app.screenshot.height}、
 * {@code app.screenshot.page-load-timeout-seconds}、{@code app.screenshot.resource-load-timeout-seconds}、
 * {@code app.screenshot.render-delay-millis}、{@code app.screenshot.jpeg-quality} 和
 * {@code app.screenshot.output-dir}。</p>
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
@Slf4j
public final class WebScreenshotUtils {

    /**
     * 截图窗口宽度，同时也是封面图的像素宽度。
     */
    private static final int VIEWPORT_WIDTH = readPositiveInt("app.screenshot.width", 1280);

    /**
     * 截图窗口高度，同时也是封面图的像素高度。
     */
    private static final int VIEWPORT_HEIGHT = readPositiveInt("app.screenshot.height", 720);

    /**
     * 页面导航与 document.readyState 等待上限。
     */
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(
            readPositiveInt("app.screenshot.page-load-timeout-seconds", 30));

    /**
     * 页面资源（图片、字体等）加载完成的最长等待时间。
     *
     * <p>封面截图只需要页面主体已经渲染，不能因为某个外部图片一直挂起就无限等待。</p>
     */
    private static final Duration RESOURCE_LOAD_TIMEOUT = Duration.ofSeconds(
            readPositiveInt("app.screenshot.resource-load-timeout-seconds", 3));

    /**
     * DOM 就绪后额外等待的渲染时间，给 Vue/React 等异步渲染留出时间。
     */
    private static final Duration RENDER_DELAY = Duration.ofMillis(
            readNonNegativeLong("app.screenshot.render-delay-millis", 1000));

    /**
     * JPEG 压缩质量，取值 0-1，数值越小文件越小、画质越低。
     */
    private static final float JPEG_QUALITY = readJpegQuality();

    /**
     * 串行化共享驱动上的导航与截图操作。
     */
    private static final ReentrantLock DRIVER_LOCK = new ReentrantLock(true);

    /**
     * 截图文件按日期分目录，便于排查和定期清理。
     */
    private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 共享的 ChromeDriver 实例；初始化失败时为 null，应用仍可启动，仅截图功能不可用。
     */
    private static volatile ChromeDriver chromeDriver;

    /**
     * 驱动初始化失败原因，用于在调用截图时给出可操作的错误提示。
     */
    private static volatile String initializationError;

    static {
        ChromeDriver initializedDriver = null;
        String error = null;
        try {
            initializedDriver = initChromeDriver();
            log.info("网页截图驱动初始化完成，窗口尺寸：{}x{}", VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        } catch (Exception e) {
            error = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            log.error("初始化网页截图驱动失败，截图功能不可用。请确认已安装 Chrome，"
                    + "或通过 -Dapp.screenshot.chrome-binary 指定浏览器路径", e);
        }
        chromeDriver = initializedDriver;
        initializationError = error;
        // JVM 退出前兜底释放浏览器进程；Spring 容器关闭时 ScreenshotServiceImpl 也会调用 quit()。
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(WebScreenshotUtils::quit, "web-screenshot-driver-shutdown"));
        } catch (IllegalStateException e) {
            log.warn("注册网页截图驱动关闭钩子失败：{}", e.getMessage());
        }
    }

    private WebScreenshotUtils() {
        throw new IllegalStateException("工具类不允许实例化");
    }

    /**
     * 使用默认的共享驱动生成网页截图，返回压缩后的 JPEG 绝对路径。
     *
     * @param webUrl 已部署网页的访问地址
     * @return 压缩后的图片文件绝对路径
     */
    public static String screenshot(String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图网址不能为空");
        }
        DRIVER_LOCK.lock();
        try {
            ChromeDriver driver = requireDriver();
            String targetUrl = webUrl.trim();
            log.info("开始生成网页截图：{}", targetUrl);
            try {
                driver.get(targetUrl);
            } catch (TimeoutException e) {
                // EAGER 只等待 DOMContentLoaded，但同步脚本仍可能阻塞导航命令。
                // 此时浏览器一般已经渲染出部分内容，继续尝试截图比直接失败更有价值。
                log.warn("页面导航等待超时，将尝试截取当前画面：{}", targetUrl);
            }
            try {
                waitForPageLoad(driver, PAGE_LOAD_TIMEOUT, RENDER_DELAY, targetUrl);
            } catch (TimeoutException e) {
                // 外部资源挂起或页面脚本阻塞时，DOM 可能已经可截图；只记录日志，不放弃截图。
                log.warn("页面加载等待超时，将截取当前已渲染画面：{}", targetUrl);
                sleepBeforeScreenshot(RENDER_DELAY);
            }
            byte[] imageBytes = takeScreenshot(driver);
            // 先保存无损 PNG，再压缩为 JPEG，尽可能保留文字边缘的清晰度。
            String pngPath = buildScreenshotPath(".png");
            saveImage(imageBytes, pngPath);
            String jpegPath = buildScreenshotPath(".jpg");
            try {
                compressImage(pngPath, jpegPath);
            } finally {
                FileUtil.del(pngPath);
            }
            log.info("网页截图生成完成：{}", jpegPath);
            return jpegPath;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成网页截图失败：{}", webUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成网页截图失败：" + e.getMessage());
        } finally {
            DRIVER_LOCK.unlock();
        }
    }

    /**
     * 等待页面主体加载完成。
     *
     * <p>只要求 DOM 进入 interactive/complete 且 body 已创建，不要求所有图片、字体等子资源完成。
     * 部署页面经常引用第三方图片，外链挂起时浏览器 load 事件可能永远不触发；cover 截图不应因此失败。
     * DOM 就绪后再有界等待资源完成，最后留出一段渲染时间给 Vue/React 等客户端渲染。</p>
     *
     * @param driver 浏览器驱动
     */
    public static void waitForPageLoad(WebDriver driver) {
        waitForPageLoad(driver, PAGE_LOAD_TIMEOUT, RENDER_DELAY, null);
    }

    /**
     * 带超时和渲染等待的页面加载方法，便于测试时缩短等待时间。
     *
     * @param driver      浏览器驱动
     * @param timeout     等待 DOM 就绪的上限
     * @param renderDelay DOM 就绪后的额外等待时间，可为 0
     */
    static void waitForPageLoad(WebDriver driver, Duration timeout, Duration renderDelay) {
        waitForPageLoad(driver, timeout, renderDelay, null);
    }

    /**
     * 带目标 URL 校验的页面加载方法。
     *
     * <p>导航完成后确认浏览器已经离开 about:blank 并渲染目标页面，避免截到空白页。</p>
     *
     * @param driver      浏览器驱动
     * @param timeout     等待 DOM 就绪的上限
     * @param renderDelay DOM 就绪后的额外等待时间，可为 0
     * @param expectedUrl 目标 URL；为 null 时不做 URL 校验
     */
    static void waitForPageLoad(WebDriver driver, Duration timeout, Duration renderDelay,
                                String expectedUrl) {
        if (driver == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "浏览器驱动不能为空");
        }
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        // DOM 可交互后即可截图；不能要求 readyState=complete，否则会被挂起的外链图片拖死。
        wait.until(currentDriver -> {
            if (expectedUrl != null && !isExpectedPage(currentDriver, expectedUrl)) {
                return false;
            }
            Object readyState = ((JavascriptExecutor) currentDriver)
                    .executeScript("return document.readyState");
            return "interactive".equals(readyState) || "complete".equals(readyState);
        });
        wait.until(currentDriver -> Boolean.TRUE.equals(
                ((JavascriptExecutor) currentDriver).executeScript("return document.body !== null")));
        // 给图片、字体等资源一个有限的机会；超时只记录 debug，继续截取当前画面。
        try {
            new WebDriverWait(driver, RESOURCE_LOAD_TIMEOUT).until(currentDriver ->
                    "complete".equals(((JavascriptExecutor) currentDriver)
                            .executeScript("return document.readyState")));
        } catch (TimeoutException e) {
            log.debug("页面资源未在 {} 内加载完成，继续截取当前画面", RESOURCE_LOAD_TIMEOUT);
        }
        sleepBeforeScreenshot(renderDelay);
    }

    /**
     * 判断浏览器是否已经离开 about:blank，并尽可能确认当前主机与目标地址一致。
     */
    private static boolean isExpectedPage(WebDriver driver, String expectedUrl) {
        try {
            String currentUrl = driver.getCurrentUrl();
            if (StrUtil.isBlank(currentUrl) || currentUrl.startsWith("about:")) {
                return false;
            }
            if (StrUtil.isBlank(expectedUrl)) {
                return true;
            }
            URI expected = URI.create(expectedUrl);
            URI actual = URI.create(currentUrl);
            return expected.getHost() == null || actual.getHost() == null
                    || expected.getHost().equalsIgnoreCase(actual.getHost());
        } catch (Exception e) {
            // 导航过程中 getCurrentUrl 可能短暂失败，等待下一轮重试。
            return false;
        }
    }

    /**
     * DOM 就绪后的固定渲染等待，便于客户端框架完成首屏绘制。
     */
    private static void sleepBeforeScreenshot(Duration renderDelay) {
        if (renderDelay == null || renderDelay.isZero() || renderDelay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(renderDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "等待页面渲染时被中断");
        }
    }

    /**
     * 保存截图字节到指定文件，自动创建父目录。
     *
     * @param imageBytes 截图字节，不能为空
     * @param outputPath 目标文件路径
     * @return 目标文件绝对路径
     */
    public static String saveImage(byte[] imageBytes, String outputPath) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图内容为空");
        }
        if (StrUtil.isBlank(outputPath)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图保存路径不能为空");
        }
        try {
            File outputFile = new File(outputPath);
            FileUtil.mkParentDirs(outputFile);
            FileUtil.writeBytes(imageBytes, outputFile);
            if (!outputFile.isFile() || outputFile.length() == 0) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "截图文件保存失败");
            }
            return outputFile.getAbsolutePath();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存截图文件失败：{}", outputPath, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存截图文件失败");
        }
    }

    /**
     * 把 PNG 等图片压缩为指定质量的 JPEG 文件。
     *
     * @param sourcePath 原始图片路径
     * @param targetPath 压缩后的图片路径，扩展名决定编码格式
     * @return 压缩文件绝对路径
     */
    public static String compressImage(String sourcePath, String targetPath) {
        if (StrUtil.isBlank(sourcePath) || StrUtil.isBlank(targetPath)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片路径不能为空");
        }
        File sourceFile = new File(sourcePath);
        if (!sourceFile.isFile() || sourceFile.length() == 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待压缩图片不存在");
        }
        try {
            File targetFile = new File(targetPath);
            FileUtil.mkParentDirs(targetFile);
            FileUtil.del(targetFile);
            ImgUtil.compress(sourceFile, targetFile, JPEG_QUALITY);
            if (!targetFile.isFile() || targetFile.length() == 0) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "截图压缩失败");
            }
            return targetFile.getAbsolutePath();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("压缩截图失败：{} -> {}", sourcePath, targetPath, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "截图压缩失败");
        }
    }

    /**
     * 判断共享驱动是否可用；初始化失败或已经关闭时返回 false。
     *
     * @return 驱动是否可用
     */
    public static boolean isDriverAvailable() {
        return chromeDriver != null;
    }

    /**
     * 销毁共享驱动并释放浏览器进程，可重复调用。
     *
     * <p>由 Spring 容器关闭回调和 JVM 关闭钩子共同调用，因此实现必须幂等。</p>
     */
    public static void quit() {
        DRIVER_LOCK.lock();
        try {
            ChromeDriver currentDriver = chromeDriver;
            chromeDriver = null;
            if (currentDriver == null) {
                return;
            }
            try {
                currentDriver.quit();
                log.info("网页截图驱动已关闭");
            } catch (Exception e) {
                log.warn("关闭网页截图驱动失败：{}", e.getMessage());
            }
        } finally {
            DRIVER_LOCK.unlock();
        }
    }

    /**
     * 初始化无头 Chrome。Selenium Manager 会在首次启动时自动匹配驱动版本。
     */
    private static ChromeDriver initChromeDriver() {
        ChromeOptions options = new ChromeOptions();
        // 只等 DOMContentLoaded，不等 load 事件；第三方图片挂起时不会阻塞截图。
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--hide-scrollbars");
        options.addArguments("--mute-audio");
        options.addArguments("--no-first-run");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-background-networking");
        options.addArguments("--force-device-scale-factor=1");
        options.addArguments("--lang=zh-CN");
        options.addArguments("--window-size=" + VIEWPORT_WIDTH + "," + VIEWPORT_HEIGHT);
        String chromeBinary = System.getProperty("app.screenshot.chrome-binary");
        if (StrUtil.isNotBlank(chromeBinary)) {
            options.setBinary(chromeBinary);
        }
        // Linux 容器通常没有 /dev/shm 且以 root 运行，需要关闭沙箱并改用磁盘临时目录。
        String osName = System.getProperty("os.name", "");
        if (osName.toLowerCase(Locale.ROOT).contains("linux")) {
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
        }
        ChromeDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
            driver.manage().timeouts().scriptTimeout(PAGE_LOAD_TIMEOUT);
            resizeToConfiguredViewport(driver);
            return driver;
        } catch (Exception e) {
            // 配置失败时立即关闭浏览器，避免残留 Chrome 进程。
            try {
                driver.quit();
            } catch (Exception ignored) {
                // 保留原始初始化异常。
            }
            throw e;
        }
    }

    /**
     * 把无头浏览器的截图视口精确调整为配置尺寸。
     *
     * <p>Chrome 的 {@code --window-size} 和 {@code window().setSize()} 设置的是外层窗口尺寸，
     * 无头模式仍会为窗口边框和工具栏预留空间，直接截图会小于预期。这里先测量外层与视口的差值，
     * 再按差值放大外层窗口，从而让截图恰好等于配置的宽高。</p>
     */
    private static void resizeToConfiguredViewport(ChromeDriver driver) {
        try {
            driver.get("about:blank");
            JavascriptExecutor javascriptExecutor = driver;
            int innerWidth = ((Number) javascriptExecutor.executeScript("return window.innerWidth")).intValue();
            int innerHeight = ((Number) javascriptExecutor.executeScript("return window.innerHeight")).intValue();
            Dimension outerSize = driver.manage().window().getSize();
            int frameWidth = Math.max(0, outerSize.getWidth() - innerWidth);
            int frameHeight = Math.max(0, outerSize.getHeight() - innerHeight);
            if (frameWidth > 0 || frameHeight > 0) {
                driver.manage().window().setSize(
                        new Dimension(VIEWPORT_WIDTH + frameWidth, VIEWPORT_HEIGHT + frameHeight));
            }
            int actualWidth = ((Number) javascriptExecutor.executeScript("return window.innerWidth")).intValue();
            int actualHeight = ((Number) javascriptExecutor.executeScript("return window.innerHeight")).intValue();
            if (actualWidth != VIEWPORT_WIDTH || actualHeight != VIEWPORT_HEIGHT) {
                log.warn("截图视口尺寸未完全匹配配置，期望：{}x{}，实际：{}x{}",
                        VIEWPORT_WIDTH, VIEWPORT_HEIGHT, actualWidth, actualHeight);
            }
        } catch (Exception e) {
            // 调整视口失败时仍可截图，只是尺寸可能略小于配置值，不能阻止驱动初始化。
            log.warn("调整截图视口失败，将使用浏览器默认视口：{}", e.getMessage());
        }
    }

    /**
     * 获取共享驱动，初始化失败时给出明确提示。
     */
    private static ChromeDriver requireDriver() {
        ChromeDriver driver = chromeDriver;
        if (driver == null) {
            String reason = StrUtil.isBlank(initializationError) ? "驱动未初始化" : initializationError;
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "网页截图驱动不可用：" + reason);
        }
        return driver;
    }

    /**
     * 调用浏览器截图接口，抽取为独立方法便于测试替换。
     */
    static byte[] takeScreenshot(WebDriver driver) {
        if (!(driver instanceof TakesScreenshot takesScreenshot)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "当前浏览器驱动不支持截图");
        }
        return takesScreenshot.getScreenshotAs(OutputType.BYTES);
    }

    /**
     * 生成带日期目录和随机文件名的截图路径，避免并发覆盖。
     */
    static String buildScreenshotPath(String suffix) {
        String dateDir = LocalDate.now().format(DATE_DIR_FORMATTER);
        File directory = new File(AppConstant.SCREENSHOT_ROOT_DIR, dateDir);
        return new File(directory, "screenshot_" + IdUtil.fastSimpleUUID() + suffix).getAbsolutePath();
    }

    private static int readPositiveInt(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("系统属性 {} 不是合法正整数：{}，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static long readNonNegativeLong(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("系统属性 {} 不是合法非负整数：{}，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static float readJpegQuality() {
        float defaultValue = 0.7f;
        String value = System.getProperty("app.screenshot.jpeg-quality");
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            float parsed = Float.parseFloat(value.trim());
            return parsed > 0 && parsed <= 1 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("系统属性 app.screenshot.jpeg-quality 不是合法小数：{}，使用默认值 {}", value, defaultValue);
            return defaultValue;
        }
    }
}
