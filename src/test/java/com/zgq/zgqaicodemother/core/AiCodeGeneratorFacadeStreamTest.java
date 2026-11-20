package com.zgq.zgqaicodemother.core;

import com.zgq.zgqaicodemother.ai.AiCodeGeneratorService;
import com.zgq.zgqaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryGuard;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryProperties;
import com.zgq.zgqaicodemother.ai.tools.ToolCallGuard;
import com.zgq.zgqaicodemother.ai.tools.ToolCallProperties;
import com.zgq.zgqaicodemother.core.handler.JsonMessageStreamHandler;
import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.core.saver.CodeFileSaverExecutor;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.preview.PreviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Deterministic stream regressions: no Spring context or external model calls.
 */
@ExtendWith(MockitoExtension.class)
class AiCodeGeneratorFacadeStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String HTML = "<!DOCTYPE html>\n<html lang=\"zh-CN\"><body>任务 \"A\"</body></html>\n";
    private static final String CSS = "body { color: #123456; }\n";
    private static final String JS = "const message = \"你好\";\nconsole.log(message);\n";
    private static final String HTML_JSON = """
            {"htmlCode":"<!DOCTYPE html>\\n<html lang=\\"zh-CN\\"><body>任务 \\"A\\"</body></html>\\n","description":"任务页面"}
            """.strip();
    private static final String MULTI_FILE_JSON = """
            {"htmlCode":"<!DOCTYPE html>\\n<html lang=\\"zh-CN\\"><body>任务 \\"A\\"</body></html>\\n","cssCode":"body { color: #123456; }\\n","jsCode":"const message = \\"你好\\";\\nconsole.log(message);\\n","description":"任务页面"}
            """.strip();

    @Mock
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Mock
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Mock
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @InjectMocks
    private AiCodeGeneratorFacade facade;

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    private final List<Path> savedDirectories = new ArrayList<>();

    /**
     * 注入真实的护轨与保护器（重试关闭、工具预算默认值），只把预览状态服务替换为 mock。
     */
    @BeforeEach
    void setUpFacadeDependencies() {
        AiRetryProperties retryProperties = new AiRetryProperties();
        retryProperties.setEnabled(false);
        ReflectionTestUtils.setField(facade, "aiRetryGuard", new AiRetryGuard(retryProperties));
        ReflectionTestUtils.setField(facade, "toolCallGuard", new ToolCallGuard(new ToolCallProperties()));
        ReflectionTestUtils.setField(facade, "previewService", mock(PreviewService.class));
    }

    @AfterEach
    void removeOnlyFilesCreatedByThisTest() throws IOException {
        for (Path directory : savedDirectories) {
            for (String filename : List.of("index.html", "style.css", "script.js")) {
                Files.deleteIfExists(directory.resolve(filename));
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void reassemblesJsonChunksAndSavesExactMultiFileSources() throws IOException {
        List<String> chunks = splitIntoCharacters(MULTI_FILE_JSON);
        stubService(CodeGenTypeEnum.MULTI_FILE);
        when(aiCodeGeneratorService.generateMultiFileCodeStream(appId, "任务页面"))
                .thenReturn(Flux.fromIterable(chunks));
        AtomicReference<MultiFileCodeResult> parsedResult = new AtomicReference<>();

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            saver.when(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.MULTI_FILE), eq(appId)))
                    .thenAnswer(invocation -> {
                        parsedResult.set(assertInstanceOf(MultiFileCodeResult.class, invocation.getArgument(0)));
                        assertUnusedOutputDirectory(CodeGenTypeEnum.MULTI_FILE);
                        File directory = (File) invocation.callRealMethod();
                        savedDirectories.add(directory.toPath());
                        return directory;
                    });

            assertEquals(chunks, facade.generateAndSaveCodeStream("任务页面", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block(TIMEOUT));

            assertEquals(HTML, parsedResult.get().getHtmlCode());
            assertEquals(CSS, parsedResult.get().getCssCode());
            assertEquals(JS, parsedResult.get().getJsCode());
            assertEquals("任务页面", parsedResult.get().getDescription());
            assertEquals(1, savedDirectories.size());
            Path directory = savedDirectories.getFirst();
            assertEquals("multi_file_" + appId, directory.getFileName().toString());
            assertEquals(HTML, Files.readString(directory.resolve("index.html"), StandardCharsets.UTF_8));
            assertEquals(CSS, Files.readString(directory.resolve("style.css"), StandardCharsets.UTF_8));
            assertEquals(JS, Files.readString(directory.resolve("script.js"), StandardCharsets.UTF_8));
            assertFiles(directory, Set.of("index.html", "style.css", "script.js"));
            saver.verify(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.MULTI_FILE), eq(appId)));
        }
    }

    @Test
    void htmlJsonSavesOnlyDecodedHtmlSource() throws IOException {
        stubService(CodeGenTypeEnum.HTML);
        when(aiCodeGeneratorService.generateHtmlCodeStream(appId, "任务页面"))
                .thenReturn(Flux.fromIterable(splitIntoCharacters(HTML_JSON)));

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            saver.when(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.HTML), eq(appId)))
                    .thenAnswer(invocation -> {
                        HtmlCodeResult result = assertInstanceOf(HtmlCodeResult.class, invocation.getArgument(0));
                        assertEquals(HTML, result.getHtmlCode());
                        assertUnusedOutputDirectory(CodeGenTypeEnum.HTML);
                        File directory = (File) invocation.callRealMethod();
                        savedDirectories.add(directory.toPath());
                        return directory;
                    });

            facade.generateAndSaveCodeStream("任务页面", CodeGenTypeEnum.HTML, appId).blockLast(TIMEOUT);

            assertEquals(1, savedDirectories.size());
            Path directory = savedDirectories.getFirst();
            assertEquals("html_" + appId, directory.getFileName().toString());
            assertEquals(HTML, Files.readString(directory.resolve("index.html"), StandardCharsets.UTF_8));
            assertFiles(directory, Set.of("index.html"));
            saver.verify(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.HTML), eq(appId)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"htmlCode\":", "{\"description\":\"missing html\"}"})
    void invalidResponsesReachSubscriberAsBusinessExceptions(String response) {
        stubService(CodeGenTypeEnum.MULTI_FILE);
        when(aiCodeGeneratorService.generateMultiFileCodeStream(appId, "invalid"))
                .thenReturn(response.isEmpty() ? Flux.empty() : Flux.just(response));

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            assertThrows(BusinessException.class, () ->
                    facade.generateAndSaveCodeStream("invalid", CodeGenTypeEnum.MULTI_FILE, appId).blockLast(TIMEOUT));
            saver.verifyNoInteractions();
        }
    }

    @Test
    void saveFailureReachesSubscriber() {
        stubService(CodeGenTypeEnum.HTML);
        when(aiCodeGeneratorService.generateHtmlCodeStream(appId, "save failure"))
                .thenReturn(Flux.just(HTML_JSON));
        BusinessException expected = new BusinessException(ErrorCode.SYSTEM_ERROR, "test save failure");

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            saver.when(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.HTML), eq(appId)))
                    .thenThrow(expected);

            BusinessException actual = assertThrows(BusinessException.class, () ->
                    facade.generateAndSaveCodeStream("save failure", CodeGenTypeEnum.HTML, appId).blockLast(TIMEOUT));

            assertSame(expected, actual);
            saver.verify(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.HTML), eq(appId)));
        }
    }

    @Test
    void upstreamFailureDoesNotSavePartialCode() {
        IllegalStateException expected = new IllegalStateException("upstream disconnected");
        stubService(CodeGenTypeEnum.HTML);
        when(aiCodeGeneratorService.generateHtmlCodeStream(appId, "upstream failure"))
                .thenReturn(Flux.concat(Flux.just(HTML_JSON), Flux.error(expected)));

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            IllegalStateException actual = assertThrows(IllegalStateException.class, () ->
                    facade.generateAndSaveCodeStream("upstream failure", CodeGenTypeEnum.HTML, appId).blockLast(TIMEOUT));

            assertSame(expected, actual);
            saver.verifyNoInteractions();
        }
    }

    @Test
    void cancellationDoesNotSavePartialCode() {
        stubService(CodeGenTypeEnum.HTML);
        when(aiCodeGeneratorService.generateHtmlCodeStream(appId, "cancel"))
                .thenReturn(Flux.concat(Flux.just("{\"htmlCode\":"), Flux.never()));

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            List<String> chunks = facade.generateAndSaveCodeStream("cancel", CodeGenTypeEnum.HTML, appId)
                    .take(1).collectList().block(TIMEOUT);

            assertEquals(List.of("{\"htmlCode\":"), chunks);
            saver.verifyNoInteractions();
        }
    }

    @Test
    void eachSubscriptionGetsItsOwnResponseBuffer() {
        List<String> chunks = splitIntoCharacters(HTML_JSON);
        stubService(CodeGenTypeEnum.HTML);
        when(aiCodeGeneratorService.generateHtmlCodeStream(appId, "repeat"))
                .thenReturn(Flux.fromIterable(chunks));
        List<HtmlCodeResult> parsedResults = new ArrayList<>();

        try (MockedStatic<CodeFileSaverExecutor> saver = mockStatic(CodeFileSaverExecutor.class)) {
            saver.when(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.HTML), eq(appId)))
                    .thenAnswer(invocation -> {
                        parsedResults.add(assertInstanceOf(HtmlCodeResult.class, invocation.getArgument(0)));
                        return new File("unused-test-output");
                    });
            Flux<String> response = facade.generateAndSaveCodeStream("repeat", CodeGenTypeEnum.HTML, appId);

            assertEquals(chunks, response.collectList().block(TIMEOUT));
            assertEquals(chunks, response.collectList().block(TIMEOUT));

            assertEquals(2, parsedResults.size());
            parsedResults.forEach(result -> assertEquals(HTML, result.getHtmlCode()));
            saver.verify(() -> CodeFileSaverExecutor.executeSaver(any(), eq(CodeGenTypeEnum.HTML), eq(appId)), times(2));
        }
    }

    private void stubService(CodeGenTypeEnum codeGenType) {
        when(aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenType))
                .thenReturn(aiCodeGeneratorService);
    }

    private void assertUnusedOutputDirectory(CodeGenTypeEnum codeGenType) {
        Path directory = Path.of(System.getProperty("user.dir"), "tmp", "code_output",
                codeGenType.getValue() + "_" + appId);
        assertFalse(Files.exists(directory), "测试输出目录已存在，拒绝覆盖：" + directory);
    }

    private static List<String> splitIntoCharacters(String response) {
        // Split inside property names and JSON escape sequences as real token streams can do.
        return IntStream.range(0, response.length())
                .mapToObj(index -> response.substring(index, index + 1)).toList();
    }

    private static void assertFiles(Path directory, Set<String> expected) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(expected, files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
    }
}
