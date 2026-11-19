package com.zgq.zgqaicodemother.ai;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zgq.zgqaicodemother.ai.model.HtmlCodeResult;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.ai.tools.FileDeleteTool;
import com.zgq.zgqaicodemother.ai.tools.FileDirReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileModifyTool;
import com.zgq.zgqaicodemother.ai.tools.FileReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.config.AiModelConfig;
import com.zgq.zgqaicodemother.config.ChatMemoryConfig;
import com.zgq.zgqaicodemother.config.ReasoningStreamingChatModelConfig;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@EnabledIfSystemProperty(named = "ai.integration.enabled", matches = "true")
@SpringBootTest(classes = {AiCodeGeneratorServiceFactory.class, AiModelConfig.class,
        ChatMemoryConfig.class, ReasoningStreamingChatModelConfig.class,
        ToolManager.class, FileWriteTool.class, FileReadTool.class, FileDirReadTool.class,
        FileModifyTool.class, FileDeleteTool.class, TestAiMemoryConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.devtools.restart.enabled=false",
                "langchain4j.open-ai.chat-model.max-retries=0",
                "langchain4j.open-ai.chat-model.log-requests=false",
                "langchain4j.open-ai.chat-model.log-responses=false",
                "langchain4j.open-ai.streaming-chat-model.log-requests=false",
                "langchain4j.open-ai.streaming-chat-model.log-responses=false",
                "langchain4j.open-ai.reasoning-streaming-chat-model.log-requests=false",
                "langchain4j.open-ai.reasoning-streaming-chat-model.log-responses=false"
        })
class AiCodeGeneratorServiceTest {

    private static final Path OUTPUT_DIRECTORY = Path.of("target", "ai-results");

    /** 集成测试沿用固定 appId，验证带 @MemoryId 的方法签名可被 AiServices 正常代理。 */
    private static final long TEST_APP_ID = 1L;

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Test
    void generateHtmlCode() throws IOException {
        Path directory = prepareOutputDirectory("html");
        HtmlCodeResult result = aiCodeGeneratorServiceFactory
                .getAiCodeGeneratorService(TEST_APP_ID, CodeGenTypeEnum.HTML)
                .generateHtmlCode(TEST_APP_ID, "做个程序员鱼皮的工作记录小工具");
        Assertions.assertNotNull(result, "AI 返回了 null");
        saveResponse(directory, "response.json", JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(result));
        String htmlCode = result.getHtmlCode();
        assertCompleteHtml(htmlCode);
        saveCode(directory, "index.html", htmlCode);
    }

    @Test
    void generateMultiFileCode() throws IOException {
        Path directory = prepareOutputDirectory("multi-file");
        MultiFileCodeResult result = aiCodeGeneratorServiceFactory
                .getAiCodeGeneratorService(TEST_APP_ID, CodeGenTypeEnum.MULTI_FILE)
                .generateMultiFileCode(TEST_APP_ID, "做个程序员鱼皮的留言板");
        Assertions.assertNotNull(result, "AI 返回了 null");
        saveResponse(directory, "response.json", JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(result));
        String htmlCode = result.getHtmlCode();
        String cssCode = result.getCssCode();
        String javascriptCode = result.getJsCode();
        Assertions.assertAll("三个文件必须有实际内容",
                () -> Assertions.assertNotNull(htmlCode, "缺少 HTML"),
                () -> Assertions.assertNotNull(cssCode, "缺少 CSS"),
                () -> Assertions.assertNotNull(javascriptCode, "缺少 JavaScript"));
        Assertions.assertFalse(htmlCode.isBlank(), "HTML 不能为空");
        Assertions.assertFalse(cssCode.isBlank(), "CSS 不能为空");
        Assertions.assertFalse(javascriptCode.isBlank(), "JavaScript 不能为空");
        assertCompleteHtml(htmlCode);
        Assertions.assertTrue(Pattern.compile(
                "(?is)<link\\b[^>]*href\\s*=\\s*['\"](?:\\./)?style\\.css['\"]").matcher(htmlCode).find(),
                "生成的 HTML 必须引用 style.css");
        Assertions.assertTrue(Pattern.compile(
                "(?is)<script\\b[^>]*src\\s*=\\s*['\"](?:\\./)?script\\.js['\"]").matcher(htmlCode).find(),
                "生成的 HTML 必须引用 script.js");
        saveCode(directory, "index.html", htmlCode);
        saveCode(directory, "style.css", cssCode);
        saveCode(directory, "script.js", javascriptCode);
    }

    /**
     * Vue 模式集成测试：真实调用模型，通过 writeFile 工具落盘，验证文件生成且没有泄露绝对路径。
     * 只在显式开启集成测试时运行。
     */
    @Test
    void generateVueProjectCodeStream() {
        long appId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        Path projectRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        Assertions.assertFalse(Files.exists(projectRoot), "测试工程目录已存在，停止运行以避免覆盖文件");
        TokenStream tokenStream = aiCodeGeneratorServiceFactory
                .getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE_PROJECT)
                .generateVueProjectCodeStream(appId, "做一个简单的个人作品集网站，包含首页和关于页面");
        List<String> chunks = Flux.<String>create(sink -> tokenStream
                        .onPartialResponse(sink::next)
                        .onToolExecuted(execution -> sink.next("[工具] " + execution.result()))
                        .onCompleteResponse(response -> sink.complete())
                        .onError(sink::error)
                        .start())
                .collectList()
                .block(Duration.ofMinutes(5));
        Assertions.assertNotNull(chunks, "模型没有返回内容");
        String response = String.join("", chunks);
        Assertions.assertFalse(response.isBlank(), "模型响应不能为空");
        Assertions.assertTrue(Files.isRegularFile(projectRoot.resolve("package.json")), "缺少 package.json");
        Assertions.assertTrue(Files.isRegularFile(projectRoot.resolve("src/App.vue")), "缺少 src/App.vue");
        String normalizedRoot = AppConstant.CODE_OUTPUT_ROOT_DIR.replace('\\', '/');
        Assertions.assertFalse(response.contains(normalizedRoot), "流式响应泄露了服务器绝对路径");
    }

    private static Path prepareOutputDirectory(String name) throws IOException {
        Path directory = OUTPUT_DIRECTORY.resolve(name).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        // 清除上次结果，避免本次失败时误把旧网页当成新结果。
        for (String filename : new String[]{"index.html", "style.css", "script.js", "response.md", "response.json"}) {
            Files.deleteIfExists(directory.resolve(filename));
        }
        return directory;
    }

    private static void saveResponse(Path directory, String filename, String response) throws IOException {
        Assertions.assertNotNull(response, "AI 返回了 null");
        Path responseFile = directory.resolve(filename);
        Files.writeString(responseFile, response, StandardCharsets.UTF_8);
        System.out.printf("%n========== AI 返回结果：%s（%d 字符）==========%n%s%n"
                        + "========== 响应已保存：%s ==========%n", directory.getFileName(), response.length(), response, responseFile);
        Assertions.assertFalse(response.isBlank(), "AI 返回了空白内容；响应见 " + responseFile);
    }

    private static void assertCompleteHtml(String htmlCode) {
        Assertions.assertNotNull(htmlCode, "缺少 HTML");
        Assertions.assertFalse(htmlCode.isBlank(), "HTML 不能为空");
        String normalized = htmlCode.toLowerCase(Locale.ROOT);
        Assertions.assertTrue(normalized.contains("<html") && normalized.contains("</html>")
                        && normalized.contains("<body") && normalized.contains("</body>"),
                "AI 返回的 HTML 结构不完整；请检查 target/ai-results 下对应的响应文件");
    }

    private static void saveCode(Path directory, String filename, String code) throws IOException {
        Path output = directory.resolve(filename);
        Files.writeString(output, code, StandardCharsets.UTF_8);
        System.out.println("生成文件：" + output);
    }
}
