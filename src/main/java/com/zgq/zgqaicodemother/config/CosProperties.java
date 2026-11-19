package com.zgq.zgqaicodemother.config;

import lombok.Data;

/**
 * 腾讯云 COS 对象存储配置。
 *
 * <p>密钥、存储桶和地域都来自配置或环境变量，不写死在业务代码中。截图默认按公共读对象上传，
 * 便于前端直接通过返回的 URL 展示封面；如果桶策略禁止对象级 ACL，可将 {@code public-read} 设为
 * false，改由桶策略统一授予读权限。</p>
 */
@Data
public class CosProperties {

    /**
     * 腾讯云 API 密钥 SecretId。
     */
    private String secretId;

    /**
     * 腾讯云 API 密钥 SecretKey。
     */
    private String secretKey;

    /**
     * 存储桶所在地域，例如 ap-guangzhou。
     */
    private String region = "ap-guangzhou";

    /**
     * 存储桶名称，通常带有 APPID 后缀，例如 bucket-1250000000。
     */
    private String bucket;

    /**
     * 对象 key 前缀，截图会追加日期目录和随机文件名。
     */
    private String keyPrefix = "screenshot/";

    /**
     * 自定义访问域名；为空时使用默认的 {@code https://{bucket}.cos.{region}.myqcloud.com}。
     */
    private String domain;

    /**
     * 是否把上传对象设置为公共读。截图不含敏感信息，默认开启以便浏览器直接访问。
     */
    private boolean publicRead = true;
}
