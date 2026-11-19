package com.zgq.zgqaicodemother.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.zgq.zgqaicodemother.config.CosProperties;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * 腾讯云 COS 上传管理器。
 *
 * <p>只负责对象存储相关能力：初始化客户端、校验本地图片、生成对象 key、上传并返回可访问 URL。
 * 不包含 appId 等业务参数，便于后续拆分为独立服务。密钥或存储桶未配置时不会阻止应用启动，
 * 上传阶段会返回明确的业务异常。</p>
 */
@Slf4j
public class CosManager {

    /**
     * 允许上传的图片扩展名，避免把任意文件写入图片目录。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /**
     * 工作流素材允许的扩展名。除常规位图外还允许 Mermaid CLI 生成的 SVG；
     * 默认仍使用 PNG，避免把可能包含脚本的 SVG 直接暴露给浏览器。
     */
    private static final Set<String> WORKFLOW_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "svg");

    /**
     * 单张图片大小上限，截图经过压缩后通常只有几百 KB。
     */
    private static final long MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024L;

    /**
     * 工作流素材大小上限。Mermaid 生成的 PNG 通常小于 2 MB，留出充足余量。
     */
    private static final long MAX_WORKFLOW_IMAGE_SIZE_BYTES = 15 * 1024 * 1024L;

    private static final String DEFAULT_WORKFLOW_KEY_PREFIX = "workflow/images/";

    private static final int CONNECTION_TIMEOUT_MILLIS = 5_000;

    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;

    private static final int MAX_CONNECTIONS = 50;

    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final CosProperties properties;

    /**
     * COS 客户端；未配置密钥或初始化失败时为 null。
     */
    private final COSClient cosClient;

    /**
     * 由 Spring 配置创建，按配置初始化 COS 客户端。
     */
    public CosManager(CosProperties properties) {
        this.properties = properties;
        this.cosClient = createClient(properties);
    }

    /**
     * 供单元测试注入模拟客户端，避免测试依赖真实对象存储。
     */
    CosManager(CosProperties properties, COSClient cosClient) {
        this.properties = properties;
        this.cosClient = cosClient;
    }

    /**
     * 上传本地图片并返回公共访问 URL。
     *
     * @param localFilePath 本地图片路径
     * @return 可直接访问的对象存储 URL
     */
    public String uploadImage(String localFilePath) {
        File imageFile = validateImageFile(localFilePath);
        String key = buildObjectKey(imageFile);
        return upload(imageFile, key);
    }

    /**
     * 上传工作流生成的图片素材（架构图、Logo 等）并返回公共访问 URL。
     *
     * <p>对象 key 完全由服务端生成：调用方只提供目录前缀，文件名使用 UUID，
     * 因此不存在路径穿越或文件名注入。仅允许常见图片扩展名，并限制单文件大小。</p>
     *
     * @param localFile 本地图片文件
     * @param keyPrefix 对象 key 前缀，例如 workflow/diagrams/
     * @return 可直接访问的对象存储 URL
     */
    public String uploadWorkflowImage(File localFile, String keyPrefix) {
        File imageFile = validateWorkflowImageFile(localFile);
        String key = normalizeWorkflowKeyPrefix(keyPrefix)
                + LocalDate.now().format(DATE_PATH_FORMATTER)
                + "/"
                + IdUtil.fastSimpleUUID()
                + "."
                + FileUtil.extName(imageFile).toLowerCase(Locale.ROOT);
        return upload(imageFile, key);
    }

    /**
     * 公共上传逻辑：设置元数据、按配置授予公共读权限并返回访问 URL。
     *
     * @param imageFile 已校验的本地图片
     * @param key       已生成的对象 key
     * @return 公共访问 URL
     */
    private String upload(File imageFile, String key) {
        COSClient client = requireClient();
        String imageUrl = buildPublicUrl(key);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(imageFile.length());
            metadata.setContentType(resolveContentType(imageFile));
            // 对象 key 含随机 UUID，内容不会变化，可以长期缓存。
            metadata.setCacheControl("public, max-age=31536000");
            PutObjectRequest request = new PutObjectRequest(properties.getBucket().trim(), key, imageFile);
            request.setMetadata(metadata);
            if (properties.isPublicRead()) {
                request.setCannedAcl(CannedAccessControlList.PublicRead);
            }
            client.putObject(request);
            log.info("图片上传对象存储成功，bucket={}，key={}，size={} bytes",
                    properties.getBucket(), key, imageFile.length());
            return imageUrl;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 不把密钥、请求签名等敏感信息拼进业务异常，详细原因只写日志。
            log.error("图片上传对象存储失败，bucket={}，key={}", properties.getBucket(), key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片上传对象存储失败");
        }
    }

    /**
     * 对象存储是否可用。仅用于状态接口和工具降级判断，不触发真实请求。
     *
     * @return 客户端已初始化且存储桶已配置时返回 true
     */
    public boolean isAvailable() {
        return cosClient != null && properties != null && StrUtil.isNotBlank(properties.getBucket());
    }

    /**
     * 关闭 COS 客户端的连接池和后台线程，可重复调用。
     */
    @PreDestroy
    public void shutdown() {
        if (cosClient == null) {
            return;
        }
        try {
            cosClient.shutdown();
            log.info("COS 客户端已关闭");
        } catch (Exception e) {
            log.warn("关闭 COS 客户端失败：{}", e.getMessage());
        }
    }

    /**
     * 创建 COS 客户端；配置不完整或初始化失败时返回 null，让主应用继续启动。
     */
    private static COSClient createClient(CosProperties properties) {
        if (!isConfigured(properties)) {
            log.warn("未配置 COS 密钥或存储桶，截图上传功能不可用");
            return null;
        }
        try {
            BasicCOSCredentials credentials = new BasicCOSCredentials(
                    properties.getSecretId().trim(), properties.getSecretKey().trim());
            ClientConfig clientConfig = new ClientConfig(new Region(resolveRegion(properties)));
            clientConfig.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
            clientConfig.setSocketTimeout(SOCKET_TIMEOUT_MILLIS);
            clientConfig.setMaxConnectionsCount(MAX_CONNECTIONS);
            COSClient client = new COSClient(credentials, clientConfig);
            log.info("COS 客户端初始化完成，bucket={}，region={}",
                    properties.getBucket(), resolveRegion(properties));
            return client;
        } catch (Exception e) {
            log.error("初始化 COS 客户端失败，截图上传功能不可用", e);
            return null;
        }
    }

    /**
     * 校验本地图片路径、大小和格式。
     */
    private static File validateImageFile(String localFilePath) {
        if (StrUtil.isBlank(localFilePath)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传图片路径不能为空");
        }
        File imageFile = new File(localFilePath);
        if (!imageFile.isFile() || imageFile.length() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传图片不存在或为空");
        }
        if (imageFile.length() > MAX_IMAGE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传图片超过大小限制");
        }
        if (!ALLOWED_EXTENSIONS.contains(FileUtil.extName(imageFile).toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传文件不是支持的图片格式");
        }
        return imageFile;
    }

    /**
     * 校验工作流素材文件：存在、非空、大小和扩展名符合要求。
     */
    private static File validateWorkflowImageFile(File imageFile) {
        if (imageFile == null || !imageFile.isFile() || imageFile.length() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传图片不存在或为空");
        }
        if (imageFile.length() > MAX_WORKFLOW_IMAGE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传图片超过大小限制");
        }
        String extension = FileUtil.extName(imageFile).toLowerCase(Locale.ROOT);
        if (!WORKFLOW_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传文件不是支持的图片格式");
        }
        return imageFile;
    }

    /**
     * 校验并规范化工作流对象 key 前缀：只允许安全字符、禁止路径穿越。
     */
    private static String normalizeWorkflowKeyPrefix(String keyPrefix) {
        String prefix = StrUtil.blankToDefault(keyPrefix, DEFAULT_WORKFLOW_KEY_PREFIX)
                .trim()
                .replace('\\', '/');
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (prefix.isEmpty() || prefix.contains("..") || prefix.indexOf(':') >= 0
                || !prefix.matches("[A-Za-z0-9._/-]+")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "对象存储 key 前缀不合法");
        }
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix;
    }

    /**
     * 校验客户端和存储桶配置，未配置时给出可操作的提示。
     */
    private COSClient requireClient() {
        if (cosClient == null) {
            String reason = isConfigured(properties)
                    ? "COS 客户端初始化失败，请检查地域、密钥和网络配置"
                    : "未配置 COS 密钥或存储桶";
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "对象存储不可用：" + reason);
        }
        if (StrUtil.isBlank(properties.getBucket())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "对象存储不可用：存储桶未配置");
        }
        return cosClient;
    }

    /**
     * 生成带前缀、日期目录和随机文件名的对象 key。
     */
    private String buildObjectKey(File imageFile) {
        String extension = FileUtil.extName(imageFile).toLowerCase(Locale.ROOT);
        return normalizeKeyPrefix(properties.getKeyPrefix())
                + LocalDate.now().format(DATE_PATH_FORMATTER)
                + "/"
                + IdUtil.fastSimpleUUID()
                + "."
                + extension;
    }

    /**
     * 统一 key 前缀格式：使用正斜杠、去掉开头斜杠、补结尾斜杠，并拒绝路径穿越。
     */
    private static String normalizeKeyPrefix(String keyPrefix) {
        String prefix = StrUtil.blankToDefault(keyPrefix, "screenshot/")
                .trim()
                .replace('\\', '/');
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (prefix.contains("..")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "对象存储 key 前缀不合法");
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix;
    }

    /**
     * 优先生成自定义域名 URL，否则使用存储桶默认域名。
     */
    private String buildPublicUrl(String key) {
        String domain = properties.getDomain();
        if (StrUtil.isNotBlank(domain)) {
            return StrUtil.removeSuffix(domain.trim(), "/") + "/" + key;
        }
        return "https://" + properties.getBucket().trim()
                + ".cos." + resolveRegion(properties)
                + ".myqcloud.com/" + key;
    }

    private static String resolveContentType(File imageFile) {
        return switch (FileUtil.extName(imageFile).toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "image/jpeg";
        };
    }

    private static String resolveRegion(CosProperties properties) {
        return StrUtil.blankToDefault(properties.getRegion(), "ap-guangzhou").trim();
    }

    private static boolean isConfigured(CosProperties properties) {
        return properties != null
                && StrUtil.isNotBlank(properties.getSecretId())
                && StrUtil.isNotBlank(properties.getSecretKey())
                && StrUtil.isNotBlank(properties.getBucket());
    }
}
