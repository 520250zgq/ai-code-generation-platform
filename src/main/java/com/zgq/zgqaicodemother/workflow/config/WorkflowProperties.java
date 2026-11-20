package com.zgq.zgqaicodemother.workflow.config;

import lombok.Data;

import java.time.Duration;

/**
 * AI 工作流配置。
 *
 * <p>所有外部素材渠道都允许单独关闭，默认值保证“没有配置任何密钥”时应用仍能启动：
 * 内容图片退化为免密钥的 Picsum，插画、架构图和 Logo 返回空素材并记录告警，
 * 工作流继续生成不含对应素材的网站。</p>
 */
@Data
public class WorkflowProperties {

    /**
     * 是否启用工作流接口。关闭后工作流 Controller 会拒绝执行请求，但不会影响原有代码生成功能。
     */
    private boolean enabled = true;

    /**
     * 同时执行的工作流数量上限，避免多个大模型/外部接口调用同时打满服务器。
     */
    private int maxConcurrentRuns = 4;

    /**
     * 单次代码生成的最长等待时间。超过后标记失败，避免请求长期挂起。
     */
    private Duration codeGenerationTimeout = Duration.ofMinutes(10);

    /**
     * 图片收集阶段的总等待时间。单个工具内部还有更细的超时，这里作为兜底。
     */
    private Duration imageCollectionTimeout = Duration.ofMinutes(4);

    /**
     * 一次工作流最多保留的图片素材数量，防止提示词和内存无限增长。
     * 默认控制在 12 张以内，避免复杂页面输出超过模型 token 上限。
     */
    private int maxTotalImages = 12;

    /**
     * 代码质检配置。
     */
    private QualityCheck qualityCheck = new QualityCheck();

    /**
     * 内容图片渠道配置。
     */
    private ContentImage contentImage = new ContentImage();

    /**
     * unDraw 插画渠道配置。
     */
    private Undraw undraw = new Undraw();

    /**
     * Mermaid 架构图配置。
     */
    private Mermaid mermaid = new Mermaid();

    /**
     * Logo 文生图配置。
     */
    private Logo logo = new Logo();

    /**
     * 代码质检配置。
     *
     * <p>质检节点会先做确定性检查（文件完整性、本地资源引用、Vue 工程结构与导入路径），
     * 再把拼接后的代码交给 AI 复核。发现问题时通过条件边回到代码生成节点修复，最多修复
     * {@code maxRepairAttempts} 次，避免模型与质检互相“踢皮球”导致无限循环。</p>
     */
    @Data
    public static class QualityCheck {

        /**
         * 是否启用代码质检。关闭后不调用 AI、不做修复循环，构建结果直接决定成败。
         */
        private boolean enabled = true;

        /**
         * 质检不通过时允许返回代码生成节点修复的次数上限。0 表示只检查、不修复。
         */
        private int maxRepairAttempts = 2;

        /**
         * 单次质检最多读取的代码文件数量，避免超大工程把提示词撑爆。
         */
        private int maxFiles = 200;

        /**
         * 单个文件最多读取的字符数，超出部分截断并标记。
         */
        private int maxFileChars = 30_000;

        /**
         * 拼接后代码总字符上限。超出后只检查前面的文件，并记录截断告警。
         */
        private int maxTotalChars = 80_000;

        /**
         * 修复提示词中携带现有代码的字符上限，仅 HTML / 多文件模式使用；
         * Vue 工程模式由模型通过文件工具读取现有文件，不需要重复贴代码。
         */
        private int maxRepairCodeChars = 60_000;

        /**
         * 单次质检最多保留的错误条数。
         */
        private int maxErrors = 20;

        /**
         * 单次质检最多保留的改进建议条数。
         */
        private int maxSuggestions = 10;

        /**
         * 质检请求中携带用户原始需求的最大长度，避免历史长需求挤占代码位置。
         */
        private int maxPromptChars = 4_000;
    }

    /**
     * 内容图片搜索配置。
     *
     * <p>provider 可选 auto / pexels / pixabay / unsplash / mock。auto 会按
     * Pexels、Pixabay、Unsplash 的顺序选择第一个已配置密钥的渠道，全部不可用时再退回
     * 免密钥的 Picsum 占位图片。</p>
     */
    @Data
    public static class ContentImage {

        private String provider = "auto";

        /**
         * 每次搜索返回的图片数量。
         */
        private int perPage = 3;

        /**
         * 一次工作流最多执行的内容图片搜索任务数。
         */
        private int maxTasks = 1;

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration requestTimeout = Duration.ofSeconds(20);

        private Pexels pexels = new Pexels();

        private Pixabay pixabay = new Pixabay();

        private Unsplash unsplash = new Unsplash();

        private Fallback fallback = new Fallback();
    }

    /**
     * Pexels 官方搜索接口配置。Pexels 目前需要申请 API Key，未配置时自动跳过。
     */
    @Data
    public static class Pexels {

        private String apiKey = "";

        private String baseUrl = "https://api.pexels.com/v1/search";
    }

    /**
     * Pixabay 官方搜索接口配置，免费账号即可申请 Key。
     */
    @Data
    public static class Pixabay {

        private String apiKey = "";

        private String baseUrl = "https://pixabay.com/api/";
    }

    /**
     * Unsplash 官方搜索接口配置，需要 Access Key。
     */
    @Data
    public static class Unsplash {

        private String accessKey = "";

        private String baseUrl = "https://api.unsplash.com/search/photos";
    }

    /**
     * 免密钥占位图片渠道。使用 Picsum 的固定 seed 地址，保证同一关键词多次生成时图片稳定。
     */
    @Data
    public static class Fallback {

        private boolean enabled = true;

        private String baseUrl = "https://picsum.photos/seed";
    }

    /**
     * unDraw 插画配置。
     */
    @Data
    public static class Undraw {

        private boolean enabled = true;

        private String baseUrl = "https://undraw.co";

        /**
         * 只接受该域名的图片地址，避免页面被篡改后把任意 URL 注入提示词。
         */
        private String cdnHost = "cdn.undraw.co";

        private int perPage = 3;

        private int maxTasks = 1;

        private Duration requestTimeout = Duration.ofSeconds(25);
    }

    /**
     * Mermaid CLI 配置。
     *
     * <p>默认输出 PNG 而不是 SVG：PNG 不会在浏览器中执行脚本，作为外部图片引用更安全。
     * 需要矢量图时可以把 output-format 改为 svg，此时必须信任 Mermaid CLI 的输出。</p>
     */
    @Data
    public static class Mermaid {

        private boolean enabled = true;

        /**
         * 可执行命令，Windows 下可以是 mmdc.cmd 或完整路径。
         */
        private String command = "mmdc";

        /**
         * png 或 svg。
         */
        private String outputFormat = "png";

        private Duration timeout = Duration.ofSeconds(120);

        private int width = 1600;

        private int height = 900;

        private String background = "transparent";

        private String theme = "default";

        /**
         * Puppeteer 启动参数。容器或 root 环境通常需要 --no-sandbox。
         */
        private String puppeteerArgs = "--no-sandbox,--disable-setuid-sandbox";

        /**
         * 可选的 Chrome/Chromium 可执行文件路径。留空时使用 Puppeteer 自带的浏览器。
         * 本地验证时可以通过该配置复用已安装的 Chrome，避免重复下载。
         */
        private String puppeteerExecutablePath = "";

        /**
         * 是否允许 Mermaid 代码引用外部图片。默认关闭，防止模型借渲染器访问内网地址。
         */
        private boolean allowExternalResources = false;

        private int maxCodeLength = 20_000;

        /**
         * 上传 COS 时的对象 key 前缀。
         */
        private String cosPrefix = "workflow/diagrams/";

        private int maxTasks = 2;
    }

    /**
     * 阿里云百炼（DashScope）文生图配置。
     */
    @Data
    public static class Logo {

        private boolean enabled = true;

        private String apiKey = "";

        /**
         * DashScope 服务根地址，不包含 /api/v1 路径。
         */
        private String baseUrl = "https://dashscope.aliyuncs.com";

        /**
         * 万相文生图模型。账号未开通对应模型时可通过配置换成可用模型。
         */
        private String model = "wan2.2-t2i-flash";

        private String size = "512*512";

        private String negativePrompt = "文字, 字母, 水印, 低质量, 模糊";

        private Duration timeout = Duration.ofMinutes(3);

        private Duration pollInterval = Duration.ofSeconds(2);

        private int maxPollAttempts = 60;

        private int maxTasks = 1;

        /**
         * 生成的临时 URL 只有有限有效期；开启后下载并转存到 COS，得到长期可访问地址。
         */
        private boolean storeToCos = true;

        private String cosPrefix = "workflow/logos/";

        /**
         * 允许下载的图片域名后缀，防止服务端被诱导请求任意地址。
         */
        private String allowedImageHosts = "aliyuncs.com,alicdn.com";
    }
}
