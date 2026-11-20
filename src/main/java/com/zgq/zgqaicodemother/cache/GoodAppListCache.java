package com.zgq.zgqaicodemother.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.mybatisflex.core.paginate.Page;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主页精选应用列表的 Redis 缓存。
 *
 * <p>首页 {@code POST /app/good/list/page/vo} 是典型的读多写少接口，每次请求都要做分页
 * count 和创建者批量查询。本缓存保存“查询参数 → 脱敏后的 AppVO 分页”映射，未命中时由调用方
 * 回源数据库，命中时直接还原 {@link Page} 返回，从而降低数据库压力。</p>
 *
 * <p><b>key 结构：</b>{@code {keyPrefix}good-app:{version}:{pageNum}:{pageSize}:{sortField}:{sortOrder}:{appNameHash}}。
 * 其中 version 来自 {@code {keyPrefix}good-app:version}，由 {@link #invalidate()} 通过 INCR 递增；
 * 应用名称只保存 SHA-256 摘要的前 16 位，避免搜索词原文进入 Redis key 和日志。
 * 所有旧版本的 key 不主动扫描删除，而是随 TTL 自然过期，因此失效操作是 O(1) 的。</p>
 *
 * <p><b>降级原则：</b>缓存层任何异常都不能影响首页主流程。不可缓存的请求、缓存未命中、
 * Redis 连接失败、JSON 脏数据等场景一律返回 {@link Optional#empty()} 或静默跳过写入，
 * 由调用方继续走数据库查询。告警按类别做 30 秒限流，避免 Redis 故障时刷屏。</p>
 *
 * <p><b>使用范围：</b>只用于 Controller 清洗过、且由服务端固定精选优先级的首页列表请求。
 * 其它带 userId、isDelete 等筛选条件的查询不能复用本缓存，否则会产生 key 冲突。</p>
 */
@Slf4j
public class GoodAppListCache {

    /**
     * 精选列表在 key 中的业务段，与版本号、分页参数用冒号分隔。
     */
    private static final String GOOD_APP_KEY_SEGMENT = "good-app:";

    /**
     * 版本号 key 的后缀：{@code keyPrefix + "good-app:version"}。
     */
    private static final String VERSION_KEY_SUFFIX = "good-app:version";

    /**
     * 空关键词使用的占位摘要，避免空字符串产生无意义的 SHA-256 计算。
     */
    private static final String BLANK_APP_NAME_HASH = "-";

    /**
     * 关键词摘要截取长度。16 位十六进制已足够区分首页搜索词，同时控制 key 长度。
     */
    private static final int APP_NAME_HASH_LENGTH = 16;

    /**
     * 与 {@code AppServiceImpl.getQueryWrapper} 保持一致的默认排序字段。
     */
    private static final String DEFAULT_SORT_FIELD = "createTime";

    /**
     * 与 {@code AppServiceImpl.getQueryWrapper} 保持一致的默认排序方向。
     */
    private static final String DEFAULT_SORT_ORDER = "descend";

    /**
     * 允许缓存的排序字段白名单。只缓存首页真正会使用的排序，避免客户端传入任意列名扩大 key 空间。
     */
    private static final Set<String> CACHEABLE_SORT_FIELDS =
            Set.of("id", "appName", "createTime", "editTime", "updateTime");

    /**
     * 单个缓存 value 的字节上限（512KB）。超过后跳过缓存，防止异常大字段挤占 Redis 内存。
     */
    private static final int MAX_VALUE_BYTES = 512 * 1024;

    /**
     * 同一类告警的最小打印间隔，避免 Redis 故障或脏数据持续出现时刷屏。
     */
    private static final long WARN_INTERVAL_MILLIS = 30_000L;

    private final StringRedisTemplate redisTemplate;

    private final JsonMapper jsonMapper;

    private final AppCacheProperties properties;

    /**
     * 规范化后的 key 前缀，保证以冒号结尾，避免拼接出 {@code cachegood-app} 这类错误 key。
     */
    private final String keyPrefix;

    /**
     * 按告警类别记录最近一次打印时间；ConcurrentHashMap 保证多线程下的限流判断安全。
     */
    private final Map<String, AtomicLong> lastWarnAt = new ConcurrentHashMap<>();

    /**
     * @param redisTemplate Spring Data Redis 模板，复用应用统一的 Redis 连接池
     * @param jsonMapper    缓存专用的 Jackson JsonMapper，由 {@link AppCacheConfig} 显式构造，
     *                      不使用 HTTP 层的 Long→String 规则，保证缓存值可以在本服务内稳定往返
     * @param properties    缓存开关、TTL 和准入限制
     */
    public GoodAppListCache(StringRedisTemplate redisTemplate,
                            JsonMapper jsonMapper,
                            AppCacheProperties properties) {
        Assert.notNull(redisTemplate, "redisTemplate 不能为空");
        Assert.notNull(jsonMapper, "jsonMapper 不能为空");
        Assert.notNull(properties, "缓存配置不能为空");
        Assert.isTrue(StrUtil.isNotBlank(properties.getKeyPrefix()), "缓存 key 前缀不能为空");
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        String prefix = properties.getKeyPrefix();
        this.keyPrefix = prefix.endsWith(":") ? prefix : prefix + ":";
    }

    /**
     * 查询缓存。只有命中且数据可正常反序列化时才返回分页对象。
     *
     * <p>请求不可缓存、缓存未启用、Redis 异常、JSON 脏数据都返回 {@link Optional#empty()}，
     * 调用方应继续查询数据库。脏数据会顺手删除对应 key，避免每次请求都重复解析失败。</p>
     *
     * @param request 首页精选列表查询参数，可为 null
     * @return 命中时的分页结果；未命中或降级时为空
     */
    public Optional<Page<AppVO>> find(AppQueryRequest request) {
        if (!isCacheable(request)) {
            return Optional.empty();
        }
        String key;
        try {
            key = buildKey(request, currentVersion());
        } catch (RuntimeException e) {
            // 版本号读取失败时无法确定当前 key，宁可回源也不能读过期版本的数据。
            warnThrottled("find-version", "读取精选应用缓存版本号失败，已降级回源数据库", e);
            return Optional.empty();
        }
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            warnThrottled("find-read", "读取精选应用缓存失败，已降级回源数据库，key={}", key, e);
            return Optional.empty();
        }
        if (StrUtil.isBlank(json)) {
            return Optional.empty();
        }
        try {
            CachedAppPage cachedPage = jsonMapper.readValue(json, CachedAppPage.class);
            if (cachedPage == null) {
                deleteDirtyKey(key);
                return Optional.empty();
            }
            return Optional.of(cachedPage.toPage());
        } catch (RuntimeException e) {
            // 旧版本格式、人工写入或内存淘汰导致的半截 JSON 均视为脏数据，删除后回源。
            warnThrottled("find-parse", "精选应用缓存反序列化失败，已删除脏 key，key={}", key, e);
            deleteDirtyKey(key);
            return Optional.empty();
        }
    }

    /**
     * 写入缓存。只缓存符合准入规则的查询；序列化失败、value 过大或 Redis 异常时
     * 只记录限流告警，不向调用方抛出异常。
     *
     * @param request 首页精选列表查询参数，可为 null
     * @param page    数据库查询结果，可为 null
     */
    public void put(AppQueryRequest request, Page<AppVO> page) {
        if (page == null || !isCacheable(request)) {
            return;
        }
        String key;
        try {
            key = buildKey(request, currentVersion());
        } catch (RuntimeException e) {
            warnThrottled("put-version", "读取精选应用缓存版本号失败，已跳过本次缓存写入", e);
            return;
        }
        String json;
        try {
            json = jsonMapper.writeValueAsString(CachedAppPage.of(page));
        } catch (RuntimeException e) {
            warnThrottled("put-serialize", "序列化精选应用缓存失败，已跳过本次缓存写入，key={}", key, e);
            return;
        }
        if (StrUtil.isBlank(json)) {
            return;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_VALUE_BYTES) {
            // 只记录 key 和大小，不打印 AppVO 内容，避免提示词等用户数据进入日志。
            warnThrottled("put-too-large",
                    "精选应用缓存单页数据超过 512KB，已跳过本次缓存写入，key={}，bytes={}", key, bytes);
            return;
        }
        Duration ttl = resolveTtl();
        try {
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (RuntimeException e) {
            warnThrottled("put-write", "写入精选应用缓存失败，已降级为直接回源，key={}", key, e);
        }
    }

    /**
     * 让所有已缓存的精选列表失效。
     *
     * <p>只对版本号 key 执行 INCR，新请求会拼出带新版本号的 key，自然读不到旧数据；
     * 旧 key 依靠 TTL 过期，不使用 {@code KEYS}/{@code SCAN} 扫描 Redis，避免阻塞实例。
     * Redis 异常时只记录日志，旧数据会继续服务到 TTL 到期。</p>
     */
    public void invalidate() {
        try {
            Long version = redisTemplate.opsForValue().increment(versionKey());
            log.debug("精选应用缓存版本号已递增，version={}", version);
        } catch (RuntimeException e) {
            warnThrottled("invalidate", "递增精选应用缓存版本号失败，旧缓存将按 TTL 自然过期", e);
        }
    }

    /**
     * 构造缓存 key，包级可见便于单元测试直接校验格式。
     *
     * <p>格式为
     * {@code keyPrefix + "good-app:" + version + ":" + pageNum + ":" + pageSize + ":"
     * + sortField + ":" + sortOrder + ":" + appNameHash}。
     * 空关键词使用 {@code -}；排序字段或方向为空时使用与
     * {@code AppServiceImpl.getQueryWrapper} 一致的默认值。关键词只保存 SHA-256 摘要，
     * 不保存原文，日志中也不会出现用户搜索词。</p>
     *
     * <p>该方法用于测试和诊断，版本号读取异常时按 0 兜底；{@link #find}/{@link #put}
     * 使用严格版本号读取，遇到 Redis 异常会直接降级，不会误用版本 0 的旧 key。
     * 传入 null 时按 {@link AppQueryRequest} 的默认分页参数构造 key，方便调用方在诊断场景下
     * 也不会因为空请求中断。</p>
     *
     * @param request 查询参数，可为 null
     * @return 当前版本下的完整缓存 key
     */
    String cacheKey(AppQueryRequest request) {
        AppQueryRequest keyRequest = request == null ? new AppQueryRequest() : request;
        long version;
        try {
            version = currentVersion();
        } catch (RuntimeException e) {
            warnThrottled("key-version", "读取精选应用缓存版本号失败，按 0 构造诊断 key", e);
            version = 0L;
        }
        return buildKey(keyRequest, version);
    }

    /**
     * 用指定版本号拼装 key，避免 find/put 在版本号异常时退化成版本 0。
     */
    private String buildKey(AppQueryRequest request, long version) {
        String appName = request.getAppName();
        String appNameHash = StrUtil.isBlank(appName)
                ? BLANK_APP_NAME_HASH
                : DigestUtil.sha256Hex(appName).substring(0, APP_NAME_HASH_LENGTH);
        String sortField = StrUtil.isBlank(request.getSortField())
                ? DEFAULT_SORT_FIELD : request.getSortField();
        String sortOrder = StrUtil.isBlank(request.getSortOrder())
                ? DEFAULT_SORT_ORDER : request.getSortOrder();
        return keyPrefix + GOOD_APP_KEY_SEGMENT + version + ":"
                + request.getPageNum() + ":" + request.getPageSize() + ":"
                + sortField + ":" + sortOrder + ":" + appNameHash;
    }

    /**
     * 判断请求是否允许使用缓存。
     *
     * <p>限制页码、每页条数、关键词长度和排序白名单，避免深分页、超长搜索词或任意列名
     * 制造大量长尾 key；不满足条件的请求直接回源，不产生任何 Redis 读写。</p>
     */
    private boolean isCacheable(AppQueryRequest request) {
        if (request == null || !properties.isEnabled()) {
            return false;
        }
        AppCacheProperties.GoodAppList goodAppList = properties.getGoodAppList();
        if (goodAppList == null || !goodAppList.isEnabled()) {
            return false;
        }
        if (request.getPageNum() < 1 || request.getPageNum() > goodAppList.getMaxPageNum()) {
            return false;
        }
        if (request.getPageSize() < 1 || request.getPageSize() > goodAppList.getMaxPageSize()) {
            return false;
        }
        String appName = request.getAppName();
        if (appName != null && appName.length() > goodAppList.getMaxAppNameLength()) {
            return false;
        }
        String sortField = request.getSortField();
        if (StrUtil.isNotBlank(sortField) && !CACHEABLE_SORT_FIELDS.contains(sortField)) {
            return false;
        }
        String sortOrder = request.getSortOrder();
        return StrUtil.isBlank(sortOrder)
                || "ascend".equals(sortOrder)
                || "descend".equals(sortOrder);
    }

    /**
     * 读取当前缓存版本号；key 不存在、为空或不是合法数字时按 0 处理。
     *
     * <p>Redis 连接异常不在此捕获，由 {@link #find}/{@link #put} 统一降级，
     * 避免版本号读取失败时误用过期的版本 0 key。</p>
     */
    private long currentVersion() {
        String version = redisTemplate.opsForValue().get(versionKey());
        if (StrUtil.isBlank(version)) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(version.trim()));
        } catch (NumberFormatException e) {
            warnThrottled("version-parse",
                    "精选应用缓存版本号不是合法数字，按 0 处理，valueLength={}", version.length());
            return 0L;
        }
    }

    /**
     * 版本号 key：{@code keyPrefix + "good-app:version"}。
     */
    private String versionKey() {
        return keyPrefix + VERSION_KEY_SUFFIX;
    }

    /**
     * 解析精选列表 TTL：优先使用专属 TTL，缺失或非正数时回退到默认 TTL，
     * 默认值也非法时使用 60 秒兜底，绝不把非正数 TTL 传给 Redis。
     */
    private Duration resolveTtl() {
        AppCacheProperties.GoodAppList goodAppList = properties.getGoodAppList();
        Duration ttl = goodAppList == null ? null : goodAppList.getTtl();
        if (isPositive(ttl)) {
            return ttl;
        }
        Duration defaultTtl = properties.getDefaultTtl();
        return isPositive(defaultTtl) ? defaultTtl : Duration.ofSeconds(60);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    /**
     * 删除无法反序列化的脏 key；删除失败只记录 debug，不能影响本次回源。
     */
    private void deleteDirtyKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.debug("删除精选应用缓存脏 key 失败，key={}", key, e);
        }
    }

    /**
     * 按类别限流告警：同一类别 30 秒内最多打印一次。
     *
     * <p>使用 CAS 保证并发下只有一个线程真正打印；最后一项为异常时 SLF4J 会附带堆栈。</p>
     */
    private void warnThrottled(String category, String format, Object... arguments) {
        long now = System.currentTimeMillis();
        AtomicLong lastWarnTimestamp = lastWarnAt.computeIfAbsent(category, ignored -> new AtomicLong());
        long previous = lastWarnTimestamp.get();
        if (previous != 0L && now - previous < WARN_INTERVAL_MILLIS) {
            return;
        }
        if (lastWarnTimestamp.compareAndSet(previous, now)) {
            log.warn(format, arguments);
        }
    }
}
