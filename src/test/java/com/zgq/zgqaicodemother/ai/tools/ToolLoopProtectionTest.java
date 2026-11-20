package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具层循环治理测试：重复写同一文件先被提示、再被熔断，内容一致的写入直接幂等跳过。
 */
class ToolLoopProtectionTest {

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    private final Path projectRoot = FileWriteTool.projectRoot(appId);

    @AfterEach
    void cleanup() {
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    void identicalWriteIsSkippedWithoutTouchingTheFile() {
        FileWriteTool tool = new FileWriteTool();

        assertTrue(tool.writeFile("src/App.vue", "<template/>", appId).contains("文件写入成功"));
        String second = tool.writeFile("src/App.vue", "<template/>", appId);

        assertTrue(second.contains("内容与现有文件一致"), second);
        assertEquals("<template/>", read("src/App.vue"));
    }

    @Test
    void repeatedWritesAreDeniedThenAborted() {
        ToolCallProperties properties = new ToolCallProperties();
        properties.setMaxCallsPerTurn(20);
        properties.setMaxRepeatsPerSubject(2);
        properties.setMaxRepeatedDenials(2);
        ToolCallGuard guard = new ToolCallGuard(properties);
        FileWriteTool tool = new FileWriteTool();
        tool.setToolCallGuard(guard);
        guard.startTurn(appId);

        assertTrue(tool.writeFile("src/App.vue", "<template>v1</template>", appId).contains("文件写入成功"));
        assertTrue(tool.writeFile("src/App.vue", "<template>v2</template>", appId).contains("文件写入成功"));

        // 第三次开始只返回提示，不再写盘：这正是“别再改这个文件了”的信号。
        String denied = tool.writeFile("src/App.vue", "<template>v3</template>", appId);
        assertTrue(denied.contains("文件写入失败"), denied);
        assertTrue(denied.contains("已重复执行"), denied);
        assertEquals("<template>v2</template>", read("src/App.vue"));

        assertTrue(tool.writeFile("src/App.vue", "<template>v4</template>", appId).contains("文件写入失败"));
        // 连续提示后仍然重复，直接熔断，终止 LangChain4j 的工具调用循环。
        assertThrows(ToolCallGuard.ToolCallLimitException.class,
                () -> tool.writeFile("src/App.vue", "<template>v5</template>", appId));

        guard.finishTurn(appId);
        // 熔断后磁盘内容仍是最后一次成功写入的版本，不会留下半截文件。
        assertEquals("<template>v2</template>", read("src/App.vue"));
    }

    @Test
    void repeatedReadsAreDeniedWithoutConsumingContext() {
        ToolCallProperties properties = new ToolCallProperties();
        properties.setMaxCallsPerTurn(20);
        properties.setMaxRepeatsPerSubject(1);
        properties.setMaxRepeatedDenials(1);
        ToolCallGuard guard = new ToolCallGuard(properties);
        FileReadTool tool = new FileReadTool();
        tool.setToolCallGuard(guard);
        new FileWriteTool().writeFile("src/App.vue", "<template/>", appId);
        guard.startTurn(appId);

        assertTrue(tool.readFile("src/App.vue", appId).contains("文件读取成功"));
        String denied = tool.readFile("src/App.vue", appId);

        assertTrue(denied.contains("文件读取失败"), denied);
        assertFalse(denied.contains("文件内容"), "被拦截的重复读取不能再次返回完整内容");
    }

    private String read(String relativePath) {
        try {
            return Files.readString(projectRoot.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 保留一个“多工具共享同一轮预算”的用例，避免后续新增工具忘记接入保护器。
     */
    @Test
    void budgetIsSharedAcrossAllFileTools() {
        ToolCallProperties properties = new ToolCallProperties();
        properties.setMaxCallsPerTurn(2);
        ToolCallGuard guard = new ToolCallGuard(properties);
        guard.startTurn(appId);
        FileWriteTool writeTool = new FileWriteTool();
        writeTool.setToolCallGuard(guard);
        FileDirReadTool dirTool = new FileDirReadTool();
        dirTool.setToolCallGuard(guard);

        assertTrue(writeTool.writeFile("src/App.vue", "<template/>", appId).contains("文件写入成功"));
        assertTrue(dirTool.readDir(".", appId).contains("目录读取成功"));

        List<Runnable> extraCalls = List.of(
                () -> writeTool.writeFile("src/main.js", "export default {}", appId),
                () -> dirTool.readDir("src", appId));
        for (Runnable call : extraCalls) {
            assertThrows(ToolCallGuard.ToolCallLimitException.class, call::run,
                    "单轮预算是所有工具共享的，不能按工具类型各自计算");
        }
    }
}
