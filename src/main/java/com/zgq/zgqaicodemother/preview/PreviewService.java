package com.zgq.zgqaicodemother.preview;

import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生成站点预览状态服务。
 *
 * <p>负责维护每个应用的“预览版本号 + 构建状态”，供前端在生成完成后立即判断能否刷新：</p>
 * <ul>
 *   <li>静态模式（HTML / 多文件）：文件一落盘就是最新版本，直接标记 READY 并递增版本号；</li>
 *   <li>Vue 工程：源码写完后先标记 BUILDING，异步构建成功后再递增版本号并标记 READY，
 *       失败则标记 FAILED。构建期间前端继续展示旧版或构建提示页，不会误以为旧版就是最终效果。</li>
 * </ul>
 *
 * <p>状态优先写入 Redis，使多实例部署下任一实例都能读到最新版本号；Redis 不可用时退化为
 * 单实例内存记录，保证预览功能本身不受影响。文件系统始终是最终真相：即使状态丢失，
 * {@link #snapshot} 也会根据磁盘产物修正为 READY / MISSING。</p>
 */
@Slf4j
public class PreviewService {

    private final StringRedisTemplate redisTemplate;

    private final PreviewProperties properties;

    /**
     * Redis 不可用时的本地降级状态。
     */
    private final Map<Long, LocalPreview> localStates = new ConcurrentHashMap<>();

    public PreviewService(StringRedisTemplate redisTemplate, PreviewProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "预览配置不能为空");
    }

    /**
     * 标记代码已生成。
     *
     * <p>静态模式此时已经可以直接预览，Vue 工程还需要构建，因此状态分别标记为 READY / BUILDING。
     * 无论哪种模式，版本号都会递增，让前端知道“磁盘内容已经换了一份”，必须重新加载 iframe。</p>
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型
     * @return 本次生成后的版本号
     */
    public long markGenerated(Long appId, CodeGenTypeEnum codeGenType) {
        if (!properties.isEnabled() || appId == null || appId <= 0 || codeGenType == null) {
            return 0L;
        }
        long revision = nextRevision(appId);
        PreviewState state = codeGenType == CodeGenTypeEnum.VUE_PROJECT
                ? PreviewState.BUILDING : PreviewState.READY;
        saveState(appId, state);
        log.info("预览状态更新，appId={}，type={}，state={}，revision={}",
                appId, codeGenType.getValue(), state, revision);
        return revision;
    }

    /**
     * 标记 Vue 工程开始构建。异步构建在真正开始前调用，避免构建排队期间状态一直停留在旧值。
     *
     * @param appId 应用 id
     */
    public void markBuildStarted(Long appId) {
        if (!properties.isEnabled() || appId == null || appId <= 0) {
            return;
        }
        saveState(appId, PreviewState.BUILDING);
    }

    /**
     * 标记 Vue 工程构建结束。
     *
     * <p>构建成功时再次递增版本号：构建期间的旧 dist 可能已经被浏览器缓存，
     * 只有换一个版本参数才能确保用户看到的是新产物。</p>
     *
     * @param appId   应用 id
     * @param success 是否构建成功
     */
    public void markBuildFinished(Long appId, boolean success) {
        if (!properties.isEnabled() || appId == null || appId <= 0) {
            return;
        }
        if (success) {
            long revision = nextRevision(appId);
            saveState(appId, PreviewState.READY);
            log.info("Vue 工程构建完成，预览已就绪，appId={}，revision={}", appId, revision);
        } else {
            saveState(appId, PreviewState.FAILED);
            log.warn("Vue 工程构建失败，预览停留在可能不是最新的版本，appId={}", appId);
        }
    }

    /**
     * 读取预览状态快照。
     *
     * <p>除了读取状态存储，还会检查磁盘上的入口文件：状态说 READY 但文件已被清理时降级为
     * MISSING，避免前端反复刷新一个 404 页面。</p>
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型，可为 null（此时只按状态返回）
     * @return 预览状态快照，绝不返回 null
     */
    public PreviewStatus snapshot(Long appId, CodeGenTypeEnum codeGenType) {
        if (appId == null || appId <= 0) {
            return new PreviewStatus(null, null, PreviewState.MISSING, 0L, false, null,
                    properties.getMissingMessage(), 0L);
        }
        if (!properties.isEnabled()) {
            // 关闭状态跟踪时仍然按磁盘产物给出“能不能看”，只是不再提供构建进度。
            boolean exists = previewFileExists(appId, codeGenType);
            PreviewState state = exists ? PreviewState.READY : PreviewState.MISSING;
            return new PreviewStatus(appId, valueOf(codeGenType), state, 0L, exists,
                    exists ? previewPath(appId, codeGenType) : null,
                    exists ? "" : properties.getMissingMessage(), 0L);
        }
        long revision = readRevision(appId);
        LocalPreview stored = readState(appId);
        PreviewState state = stored == null ? PreviewState.MISSING : stored.state;
        long updatedAt = stored == null ? 0L : stored.updatedAtMillis;
        boolean exists = previewFileExists(appId, codeGenType);
        if (state == PreviewState.READY && !exists) {
            state = PreviewState.MISSING;
        }
        if (state == PreviewState.MISSING && exists) {
            // 状态记录丢失（例如 Redis 过期）但产物还在：按可预览处理，只是版本号可能从 0 开始。
            state = PreviewState.READY;
        }
        boolean ready = state == PreviewState.READY;
        return new PreviewStatus(appId, valueOf(codeGenType), state, revision, ready,
                exists ? previewPath(appId, codeGenType) : null, messageOf(state), updatedAt);
    }

    /**
     * 清理应用的状态记录；应用被删除时调用。
     *
     * @param appId 应用 id
     */
    public void evict(Long appId) {
        if (appId == null) {
            return;
        }
        localStates.remove(appId);
        try {
            redisTemplate.delete(revisionKey(appId));
            redisTemplate.delete(stateKey(appId));
        } catch (RuntimeException e) {
            log.warn("清理预览状态失败，appId={}，原因={}", appId, e.getMessage());
        }
    }

    /**
     * 判断磁盘上是否已有可预览的入口文件。
     *
     * <p>Vue 工程必须等 dist 构建完成；静态模式直接检查 index.html。</p>
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型
     * @return 可以直接预览返回 true
     */
    boolean previewFileExists(Long appId, CodeGenTypeEnum codeGenType) {
        if (appId == null || appId <= 0 || codeGenType == null) {
            return false;
        }
        Path codeDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                codeGenType.getValue() + "_" + appId).toAbsolutePath().normalize();
        if (!Files.isDirectory(codeDir)) {
            return false;
        }
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            return Files.isRegularFile(codeDir.resolve("dist").resolve("index.html"));
        }
        return Files.isRegularFile(codeDir.resolve("index.html"));
    }

    /**
     * 预览相对路径，交由前端拼接预览根地址。
     */
    String previewPath(Long appId, CodeGenTypeEnum codeGenType) {
        if (appId == null || codeGenType == null) {
            return null;
        }
        return "static/" + codeGenType.getValue() + "_" + appId + "/";
    }

    /**
     * 递增版本号：优先 Redis（多实例一致），失败时退化为本地计数。
     *
     * <p>本地计数会继承读到的 Redis 最大值，避免 Redis 恢复后版本号回退导致 iframe 不刷新。</p>
     */
    private long nextRevision(Long appId) {
        try {
            Long value = redisTemplate.opsForValue().increment(revisionKey(appId));
            if (value != null) {
                expireSafely(revisionKey(appId));
                localRevision(appId).updateAndGet(current -> Math.max(current, value));
                return value;
            }
        } catch (RuntimeException e) {
            log.warn("预览版本号写入 Redis 失败，暂用本地计数，appId={}，原因={}", appId, e.getMessage());
        }
        return localRevision(appId).incrementAndGet();
    }

    /**
     * 读取版本号；Redis 不可用时返回本地计数。
     */
    private long readRevision(Long appId) {
        try {
            String value = redisTemplate.opsForValue().get(revisionKey(appId));
            if (value != null) {
                long revision = Long.parseLong(value.trim());
                localRevision(appId).updateAndGet(current -> Math.max(current, revision));
                return revision;
            }
        } catch (RuntimeException e) {
            log.debug("读取预览版本号失败，使用本地记录，appId={}，原因={}", appId, e.getMessage());
        }
        LocalPreview local = localStates.get(appId);
        return local == null ? 0L : local.revision.get();
    }

    /**
     * 写入状态：优先 Redis，失败时写本地。
     */
    private void saveState(Long appId, PreviewState state) {
        long now = System.currentTimeMillis();
        LocalPreview local = localStates.computeIfAbsent(appId, key -> new LocalPreview());
        local.state = state;
        local.updatedAtMillis = now;
        cleanupLocalIfNeeded();
        try {
            redisTemplate.opsForValue().set(stateKey(appId), state.name(), properties.getTtl());
        } catch (RuntimeException e) {
            log.warn("预览状态写入 Redis 失败，已降级为本地记录，appId={}，原因={}", appId, e.getMessage());
        }
    }

    /**
     * 读取状态：优先 Redis，缺失时回退本地。
     */
    private LocalPreview readState(Long appId) {
        try {
            String value = redisTemplate.opsForValue().get(stateKey(appId));
            if (value != null && !value.isBlank()) {
                LocalPreview local = new LocalPreview();
                local.state = PreviewState.parse(value);
                local.updatedAtMillis = System.currentTimeMillis();
                return local;
            }
        } catch (RuntimeException e) {
            log.debug("读取预览状态失败，使用本地记录，appId={}，原因={}", appId, e.getMessage());
        }
        return localStates.get(appId);
    }

    /**
     * 刷新版本号 key 的过期时间，避免长期占用 Redis 内存。
     */
    private void expireSafely(String key) {
        Duration ttl = properties.getTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redisTemplate.expire(key, ttl);
        } catch (RuntimeException e) {
            // 过期时间设置失败只影响内存回收，不影响正确性，忽略即可。
            log.debug("设置预览版本号过期时间失败，key={}，原因={}", key, e.getMessage());
        }
    }

    /**
     * 本地降级状态超过上限时清理最旧记录，保证内存有界。
     */
    private void cleanupLocalIfNeeded() {
        int maxEntries = Math.max(1, properties.getMaxLocalEntries());
        if (localStates.size() <= maxEntries) {
            return;
        }
        Iterator<Long> iterator = localStates.keySet().iterator();
        while (localStates.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private AtomicLong localRevision(Long appId) {
        return localStates.computeIfAbsent(appId, key -> new LocalPreview()).revision;
    }

    private String revisionKey(Long appId) {
        return properties.getKeyPrefix() + "revision:" + appId;
    }

    private String stateKey(Long appId) {
        return properties.getKeyPrefix() + "state:" + appId;
    }

    private String messageOf(PreviewState state) {
        return switch (state) {
            case BUILDING -> properties.getBuildingMessage();
            case FAILED -> properties.getFailedMessage();
            case MISSING -> properties.getMissingMessage();
            case READY -> "";
        };
    }

    private static String valueOf(CodeGenTypeEnum codeGenType) {
        return codeGenType == null ? null : codeGenType.getValue();
    }

    /**
     * 单实例内的降级预览状态。
     */
    private static final class LocalPreview {

        private final AtomicLong revision = new AtomicLong();

        private volatile PreviewState state = PreviewState.MISSING;

        private volatile long updatedAtMillis;
    }
}
