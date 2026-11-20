package com.zgq.zgqaicodemother.cache;

import cn.hutool.crypto.digest.DigestUtil;
import com.mybatisflex.core.paginate.Page;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import com.zgq.zgqaicodemother.model.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GoodAppListCache} 的单元测试。
 *
 * <p>不连接真实 Redis 和 MySQL：Redis 读写用 Mockito 模拟，并用内存 Map 保存写入的 value，
 * 以便验证“写入后同一请求能命中”的完整链路；JSON 使用真实的 Jackson 3 JsonMapper，
 * 覆盖 LocalDateTime 等字段的序列化与反序列化。</p>
 */
@ExtendWith(MockitoExtension.class)
class GoodAppListCacheTest {

    private static final String KEY_PREFIX = "test:cache:";
    private static final String VERSION_KEY = KEY_PREFIX + "good-app:version";
    private static final LocalDateTime CREATE_TIME = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 模拟 Redis 中的字符串数据；配合 Mockito Answer 完成 set/get/increment 的闭环。
     */
    private final Map<String, String> redisStore = new ConcurrentHashMap<>();

    private AppCacheProperties properties;

    private GoodAppListCache cache;

    @BeforeEach
    void setUp() {
        properties = new AppCacheProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        cache = new GoodAppListCache(redisTemplate, jsonMapper, properties);

        // 绝大多数用例都会经过 opsForValue，但对不可缓存请求的用例不应产生任何 Redis 调用，
        // 因此统一使用 lenient 声明底层桩，避免 Mockito 严格模式报 UnnecessaryStubbing。
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        lenient().doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisStore.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        lenient().when(valueOperations.increment(VERSION_KEY)).thenAnswer(invocation -> {
            long nextVersion = Long.parseLong(redisStore.getOrDefault(VERSION_KEY, "0")) + 1L;
            redisStore.put(VERSION_KEY, Long.toString(nextVersion));
            return nextVersion;
        });
    }

    @Test
    void findReturnsEmptyWhenCacheMisses() {
        Optional<Page<AppVO>> result = cache.find(request());

        assertTrue(result.isEmpty());
    }

    @Test
    void putThenFindReturnsSameRecordsAndPagination() {
        AppQueryRequest request = request();
        // 带上搜索词，覆盖 key 中 SHA-256 摘要的写入与命中路径。
        request.setAppName("精选");
        Page<AppVO> source = appPage();
        // 必须在 verify 之前算出 key：cacheKey 内部会读取版本号，
        // 若放 in verify 参数里会被 Mockito 当成对 valueOperations 的又一次验证目标。
        String expectedKey = cache.cacheKey(request);

        cache.put(request, source);
        Optional<Page<AppVO>> cached = cache.find(request);

        assertTrue(cached.isPresent());
        Page<AppVO> page = cached.get();
        assertEquals(2L, page.getPageNumber());
        assertEquals(10L, page.getPageSize());
        assertEquals(25L, page.getTotalRow());
        assertEquals(3L, page.getTotalPage());
        assertEquals(1, page.getRecords().size());

        AppVO appVO = page.getRecords().get(0);
        assertEquals(1001L, appVO.getId());
        assertEquals("精选应用", appVO.getAppName());
        assertEquals(CREATE_TIME, appVO.getCreateTime());
        assertNotNull(appVO.getUser());
        assertEquals(2002L, appVO.getUser().getId());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(expectedKey), json.capture(), eq(Duration.ofSeconds(60)));
        // 缓存 value 只保存 AppVO 展示字段，不应把带密码的实体写进 Redis。
        assertTrue(json.getValue().contains("精选应用"));
        assertFalse(json.getValue().contains("userPassword"));
        assertFalse(json.getValue().contains("password"));
    }

    @Test
    void invalidateChangesVersionSoOldEntriesAreNoLongerRead() {
        AppQueryRequest request = request();
        cache.put(request, appPage());
        String oldKey = cache.cacheKey(request);
        assertTrue(redisStore.containsKey(oldKey));

        cache.invalidate();

        String newKey = cache.cacheKey(request);
        assertNotEquals(oldKey, newKey);
        assertEquals("1", redisStore.get(VERSION_KEY));
        // 旧 key 不主动扫描删除，交给 TTL 自然过期。
        assertTrue(redisStore.containsKey(oldKey));
        assertTrue(cache.find(request).isEmpty());
        verify(valueOperations).increment(VERSION_KEY);
    }

    @Test
    void redisFailureOnReadDegradesFindAndPutSilently() {
        AppQueryRequest request = request();
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOperations).get(VERSION_KEY);

        assertTrue(cache.find(request).isEmpty());
        assertDoesNotThrow(() -> cache.put(request, appPage()));
        // 版本号都读不到时不能确定当前 key，put 应直接跳过写入。
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisFailureOnWriteDoesNotThrow() {
        AppQueryRequest request = request();
        doThrow(new RedisConnectionFailureException("redis write down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(() -> cache.put(request, appPage()));
    }

    @Test
    void uncacheableRequestsNeverReadOrWriteRedis() {
        clearInvocations(redisTemplate, valueOperations);
        AppQueryRequest pageNumTooLarge = request();
        pageNumTooLarge.setPageNum(51);
        AppQueryRequest pageSizeTooLarge = request();
        pageSizeTooLarge.setPageSize(21);
        AppQueryRequest invalidSortField = request();
        // cover 在数据库查询白名单内，但不在缓存白名单内，必须直接回源。
        invalidSortField.setSortField("cover");
        AppQueryRequest invalidSortOrder = request();
        invalidSortOrder.setSortOrder("asc");
        AppQueryRequest appNameTooLong = request();
        appNameTooLong.setAppName("a".repeat(65));
        Page<AppVO> page = appPage();

        for (AppQueryRequest request : List.of(pageNumTooLarge, pageSizeTooLarge,
                invalidSortField, invalidSortOrder, appNameTooLong)) {
            assertTrue(cache.find(request).isEmpty());
            cache.put(request, page);
        }

        verifyNoInteractions(redisTemplate, valueOperations);
    }

    @Test
    void nullInputsDegradeSilently() {
        clearInvocations(redisTemplate, valueOperations);

        assertTrue(cache.find(null).isEmpty());
        cache.put(null, appPage());
        cache.put(request(), null);

        verifyNoInteractions(redisTemplate, valueOperations);
    }

    @Test
    void disabledCacheNeverReadsOrWritesRedis() {
        properties.setEnabled(false);
        clearInvocations(redisTemplate, valueOperations);

        assertTrue(cache.find(request()).isEmpty());
        cache.put(request(), appPage());

        verifyNoInteractions(redisTemplate, valueOperations);
    }

    @Test
    void corruptedJsonReturnsEmptyAndDeletesDirtyKey() {
        AppQueryRequest request = request();
        String key = cache.cacheKey(request);
        when(valueOperations.get(key)).thenReturn("{not-a-cached-page");

        assertTrue(cache.find(request).isEmpty());

        verify(redisTemplate).delete(key);
    }

    @Test
    void cacheKeyUsesHashDefaultsAndVersion() {
        AppQueryRequest request = request();
        request.setSortField(null);
        request.setSortOrder(null);
        request.setAppName("我的应用");

        String key = cache.cacheKey(request);

        assertEquals(KEY_PREFIX + "good-app:0:1:10:createTime:descend:"
                + DigestUtil.sha256Hex("我的应用").substring(0, 16), key);
        assertFalse(key.contains("我的应用"));

        request.setAppName("   ");
        assertEquals(KEY_PREFIX + "good-app:0:1:10:createTime:descend:-", cache.cacheKey(request));
        // cacheKey 是诊断入口，null 请求也不能抛出异常。
        assertEquals(KEY_PREFIX + "good-app:0:1:10:createTime:descend:-", cache.cacheKey(null));
    }

    @Test
    void cacheKeyFallsBackToVersionZeroWhenVersionCannotBeRead() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOperations).get(VERSION_KEY);

        String key = cache.cacheKey(request());

        assertEquals(KEY_PREFIX + "good-app:0:1:10:createTime:descend:-", key);
    }

    @Test
    void cachedPageRestoresFieldsAndFallsBackToComputedTotalPage() {
        CachedAppPage cachedPage = new CachedAppPage();
        cachedPage.setPageNumber(2);
        cachedPage.setPageSize(10);
        cachedPage.setTotalRow(21);
        cachedPage.setTotalPage(0);
        cachedPage.setRecords(null);

        Page<AppVO> page = cachedPage.toPage();

        assertEquals(2L, page.getPageNumber());
        assertEquals(10L, page.getPageSize());
        assertEquals(21L, page.getTotalRow());
        assertEquals(3L, page.getTotalPage());
        assertTrue(page.getRecords().isEmpty());
    }

    @Test
    void cachedPagePreservesValidTotalPageAndRecords() {
        CachedAppPage cachedPage = CachedAppPage.of(appPage());
        cachedPage.setTotalPage(7);

        Page<AppVO> page = cachedPage.toPage();

        assertEquals(7L, page.getTotalPage());
        assertEquals(1, page.getRecords().size());
        assertEquals(1001L, page.getRecords().get(0).getId());
    }

    @Test
    void oversizedSerializedPageIsSkipped() {
        AppVO appVO = appVO();
        appVO.setInitPrompt("x".repeat(600 * 1024));
        Page<AppVO> page = new Page<>(1, 10, 1L);
        page.setRecords(List.of(appVO));

        cache.put(request(), page);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    private AppQueryRequest request() {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);
        request.setSortField("createTime");
        request.setSortOrder("descend");
        return request;
    }

    private Page<AppVO> appPage() {
        Page<AppVO> page = new Page<>(2, 10, 25L);
        page.setTotalPage(3);
        page.setRecords(List.of(appVO()));
        return page;
    }

    private AppVO appVO() {
        AppVO appVO = new AppVO();
        appVO.setId(1001L);
        appVO.setAppName("精选应用");
        appVO.setCreateTime(CREATE_TIME);
        UserVO user = new UserVO();
        user.setId(2002L);
        user.setUserName("张三");
        appVO.setUser(user);
        return appVO;
    }
}
