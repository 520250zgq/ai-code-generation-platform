package com.zgq.zgqaicodemother.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.service.ScreenshotService;
import com.zgq.zgqaicodemother.utils.WebScreenshotUtils;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用截图服务实现：本地生成截图，上传对象存储，并清理本地临时文件。
 *
 * <p>方法只依赖网址和对象存储，不读取应用、用户等业务数据。截图驱动在整个应用内共享，
 * 应用停止时在这里主动销毁，避免遗留 Chrome 进程。</p>
 */
@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {

    @Resource
    private CosManager cosManager;

    /**
     * 是否真正使用过截图驱动。
     *
     * <p>{@link WebScreenshotUtils} 的静态代码块会在类初始化时启动浏览器；如果应用从未使用截图，
     * 关闭阶段不应仅仅为了调用 quit() 而触发驱动初始化。只有实际执行过截图才在这里销毁。</p>
     */
    private final AtomicBoolean screenshotDriverUsed = new AtomicBoolean(false);

    /**
     * 生成截图并上传，返回对象存储地址。
     *
     * <p>无论上传成功还是失败，本地临时图片都会在 finally 中删除，避免长期运行后占满磁盘。</p>
     */
    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        String normalizedUrl = normalizeWebUrl(webUrl);
        // 从这里开始会触发 WebScreenshotUtils 类初始化并启动共享浏览器，关闭时需要主动销毁。
        screenshotDriverUsed.set(true);
        String localImagePath = null;
        try {
            localImagePath = WebScreenshotUtils.screenshot(normalizedUrl);
            String imageUrl = cosManager.uploadImage(localImagePath);
            if (StrUtil.isBlank(imageUrl)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "上传截图后未返回图片地址");
            }
            log.info("网页截图已生成并上传，webUrl={}，imageUrl={}", normalizedUrl, imageUrl);
            return imageUrl;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成并上传网页截图失败，webUrl={}", normalizedUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成并上传网页截图失败");
        } finally {
            if (localImagePath != null && !FileUtil.del(localImagePath)) {
                log.warn("删除本地截图文件失败：{}", localImagePath);
            }
        }
    }

    /**
     * 应用停止前销毁共享的截图驱动，释放浏览器资源。
     */
    @PreDestroy
    public void destroy() {
        if (screenshotDriverUsed.get()) {
            WebScreenshotUtils.quit();
        }
    }

    /**
     * 只允许 http/https 且带主机名的地址，避免把 file、ftp 等协议交给浏览器。
     */
    private String normalizeWebUrl(String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图网址不能为空");
        }
        String trimmedUrl = webUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmedUrl);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图网址格式不正确");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图网址只支持 http 或 https");
        }
        if (StrUtil.isBlank(uri.getHost())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图网址缺少主机名");
        }
        return uri.toString();
    }
}
