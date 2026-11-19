package com.zgq.zgqaicodemother.service;

/**
 * 通用网页截图服务：根据网页地址生成截图并上传对象存储，返回图片访问地址。
 *
 * <p>接口只接收网址，不包含 appId 等具体业务参数，便于后续改造为独立微服务或被多个业务复用。
 * 本地截图、图片压缩和对象存储上传都由实现类内部完成。</p>
 */
public interface ScreenshotService {

    /**
     * 生成网页截图并上传对象存储。
     *
     * @param webUrl 待截图的网页地址，只支持 http/https
     * @return 上传后的图片访问 URL
     */
    String generateAndUploadScreenshot(String webUrl);
}
