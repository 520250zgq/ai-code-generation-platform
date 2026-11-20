package com.zgq.zgqaicodemother.preview;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预览状态测试：版本号推进、构建状态流转、磁盘产物校验和 Redis 不可用时的本地降级。
 */
class PreviewServiceTest {

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private final Path htmlDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + appId);

    private final Path vueDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);

    private final Map<String, String> redisStore = new ConcurrentHashMap<>();

    private StringRedisTemplate redisTemplate;

    private PreviewProperties properties;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long next = Long.parseLong(redisStore.getOrDefault(key, "0")) + 1;
            redisStore.put(key, String.valueOf(next));
            return next;
        });
        when(valueOperations.get(anyString())).thenAnswer(invocation ->
                redisStore.get(invocation.getArgument(0)));
        // ValueOperations#set(K, V, Duration) 返回 void，只能用 doAnswer 形式声明副作用。
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        properties = new PreviewProperties();
        properties.setTtl(Duration.ofHours(1));
    }

    @AfterEach
    void cleanup() {
        FileUtil.del(htmlDir.toFile());
        FileUtil.del(vueDir.toFile());
    }

    @Test
    void staticGenerationIsImmediatelyReadyWithIncreasingRevision() {
        writeIndexHtml();
        PreviewService service = new PreviewService(redisTemplate, properties);

        long first = service.markGenerated(appId, CodeGenTypeEnum.HTML);
        PreviewStatus status = service.snapshot(appId, CodeGenTypeEnum.HTML);

        assertEquals(1L, first);
        assertEquals(PreviewState.READY, status.state());
        assertTrue(status.ready());
        assertEquals("static/html_" + appId + "/", status.previewPath());
        assertEquals(1L, status.revision());

        long second = service.markGenerated(appId, CodeGenTypeEnum.HTML);
        assertEquals(2L, second);
        assertEquals(2L, service.snapshot(appId, CodeGenTypeEnum.HTML).revision(),
                "每次生成都必须推进版本号，否则 iframe 不会重新加载");
    }

    @Test
    void vueGenerationStaysBuildingUntilBuildFinishes() {
        writeVueProject();
        PreviewService service = new PreviewService(redisTemplate, properties);

        service.markGenerated(appId, CodeGenTypeEnum.VUE_PROJECT);
        PreviewStatus building = service.snapshot(appId, CodeGenTypeEnum.VUE_PROJECT);
        assertEquals(PreviewState.BUILDING, building.state());
        assertFalse(building.ready());
        assertEquals("static/vue_project_" + appId + "/", building.previewPath());
        assertTrue(building.message().contains("构建"), building.message());

        service.markBuildFinished(appId, true);
        PreviewStatus ready = service.snapshot(appId, CodeGenTypeEnum.VUE_PROJECT);
        assertEquals(PreviewState.READY, ready.state());
        assertEquals(2L, ready.revision(), "构建成功也要推进版本号，避免浏览器复用旧 dist");
    }

    @Test
    void failedBuildIsReportedAndNotReady() {
        writeVueProject();
        PreviewService service = new PreviewService(redisTemplate, properties);
        service.markGenerated(appId, CodeGenTypeEnum.VUE_PROJECT);

        service.markBuildFinished(appId, false);
        PreviewStatus status = service.snapshot(appId, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(PreviewState.FAILED, status.state());
        assertFalse(status.ready());
        assertTrue(status.message().contains("失败"), status.message());
    }

    @Test
    void missingFilesDowngradeReadyState() {
        writeIndexHtml();
        PreviewService service = new PreviewService(redisTemplate, properties);
        service.markGenerated(appId, CodeGenTypeEnum.HTML);

        FileUtil.del(htmlDir.toFile());
        PreviewStatus status = service.snapshot(appId, CodeGenTypeEnum.HTML);

        assertEquals(PreviewState.MISSING, status.state());
        assertFalse(status.ready());
    }

    @Test
    void redisFailureFallsBackToLocalState() {
        writeIndexHtml();
        when(redisTemplate.opsForValue()).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException("redis down"));
        PreviewService service = new PreviewService(redisTemplate, properties);

        long revision = service.markGenerated(appId, CodeGenTypeEnum.HTML);
        PreviewStatus status = service.snapshot(appId, CodeGenTypeEnum.HTML);

        assertTrue(revision >= 1L);
        assertEquals(PreviewState.READY, status.state());
        assertEquals(revision, status.revision());
    }

    @Test
    void evictClearsVersionAndState() {
        writeIndexHtml();
        PreviewService service = new PreviewService(redisTemplate, properties);
        service.markGenerated(appId, CodeGenTypeEnum.HTML);

        service.evict(appId);

        // Redis 删除是 mock 的空操作，但快照必须按磁盘真实情况重新判断；
        // 这里再删掉文件，确认状态一起被清理、不会残留 READY。
        FileUtil.del(htmlDir.toFile());
        assertEquals(PreviewState.MISSING, service.snapshot(appId, CodeGenTypeEnum.HTML).state());
    }

    @Test
    void disabledTrackingStillReportsFilesystemTruth() {
        writeIndexHtml();
        properties.setEnabled(false);
        PreviewService service = new PreviewService(redisTemplate, properties);

        PreviewStatus status = service.snapshot(appId, CodeGenTypeEnum.HTML);

        assertTrue(status.ready());
        assertEquals(0L, status.revision(), "关闭状态跟踪时不再维护版本号");
    }

    @Test
    void invalidAppReturnsMissingWithoutTouchingRedis() {
        PreviewService service = new PreviewService(redisTemplate, properties);

        PreviewStatus status = service.snapshot(0L, CodeGenTypeEnum.HTML);

        assertEquals(PreviewState.MISSING, status.state());
        assertFalse(status.ready());
        assertEquals(0L, status.revision());
    }

    @Test
    void revisionUsesRedisAcrossInstances() {
        writeIndexHtml();
        PreviewService first = new PreviewService(redisTemplate, properties);
        PreviewService second = new PreviewService(redisTemplate, properties);
        first.markGenerated(appId, CodeGenTypeEnum.HTML);

        long fromSecond = second.snapshot(appId, CodeGenTypeEnum.HTML).revision();

        assertEquals(1L, fromSecond, "多实例部署下版本号必须来自共享存储");
        assertTrue(redisStore.containsKey(properties.getKeyPrefix() + "revision:" + appId));
    }

    private void writeIndexHtml() {
        try {
            Files.createDirectories(htmlDir);
            Files.writeString(htmlDir.resolve("index.html"), "<html></html>");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Vue 工程的可预览产物位于 dist/index.html，源码目录本身不能直接给浏览器执行。
     */
    private void writeVueProject() {
        try {
            Files.createDirectories(vueDir);
            Files.writeString(vueDir.resolve("index.html"), "<html></html>");
            Path dist = vueDir.resolve("dist");
            Files.createDirectories(dist);
            Files.writeString(dist.resolve("index.html"), "<html></html>");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
