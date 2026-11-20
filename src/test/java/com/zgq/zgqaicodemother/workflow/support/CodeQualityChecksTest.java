package com.zgq.zgqaicodemother.workflow.support;

import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.model.ProjectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码确定性静态检查测试：只覆盖会导致报错、白屏或构建失败的规则。
 */
class CodeQualityChecksTest {

    @Test
    void validHtmlProjectPasses() {
        List<String> errors = CodeQualityChecks.check(code(Map.of(
                "index.html", "<!DOCTYPE html><html><body><h1>ok</h1></body></html>")),
                CodeGenTypeEnum.HTML, 20);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void missingOrTruncatedIndexHtmlIsReported() {
        List<String> missing = CodeQualityChecks.check(code(Map.of("style.css", "body{}")),
                CodeGenTypeEnum.HTML, 20);
        assertTrue(missing.stream().anyMatch(error -> error.contains("缺少 index.html")), missing.toString());

        List<String> truncated = CodeQualityChecks.check(code(Map.of(
                "index.html", "<html><body><h1>ok</h1>")), CodeGenTypeEnum.HTML, 20);
        assertTrue(truncated.stream().anyMatch(error -> error.contains("</html>")), truncated.toString());
    }

    @Test
    void markdownCodeFenceIsReported() {
        List<String> errors = CodeQualityChecks.check(code(Map.of(
                "index.html", "<html><body>```html<h1>ok</h1>```</body></html>")),
                CodeGenTypeEnum.HTML, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("代码围栏")), errors.toString());
    }

    @Test
    void missingLocalReferenceIsReportedForMultiFile() {
        List<String> errors = CodeQualityChecks.check(code(Map.of(
                "index.html", """
                        <html><head><link rel="stylesheet" href="style.css"></head>
                        <body><script src="script.js"></script></body></html>
                        """,
                "style.css", "body{}")), CodeGenTypeEnum.MULTI_FILE, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("script.js")), errors.toString());
        assertTrue(errors.stream().noneMatch(error -> error.contains("style.css")), errors.toString());
    }

    @Test
    void externalAndAnchorReferencesAreIgnored() {
        List<String> errors = CodeQualityChecks.check(code(Map.of(
                "index.html", """
                        <html><body>
                        <a href="#top">top</a>
                        <img src="https://picsum.photos/200">
                        <script src="//cdn.example.com/lib.js"></script>
                        </body></html>
                        """)), CodeGenTypeEnum.MULTI_FILE, 20);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validVueProjectPasses() {
        List<String> errors = CodeQualityChecks.check(code(vueProject()), CodeGenTypeEnum.VUE_PROJECT, 20);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void vueProjectChecksPackageJsonAndEntryFiles() {
        Map<String, String> files = new LinkedHashMap<>(vueProject());
        files.put("package.json", "{invalid json");
        files.remove("src/main.js");

        List<String> errors = CodeQualityChecks.check(code(files), CodeGenTypeEnum.VUE_PROJECT, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("package.json 不是合法")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("src/main.js")), errors.toString());
    }

    @Test
    void vueProjectChecksBuildScriptAndVueDependency() {
        Map<String, String> files = new LinkedHashMap<>(vueProject());
        files.put("package.json", "{\"scripts\":{},\"dependencies\":{\"vue-router\":\"^4\"}}");

        List<String> errors = CodeQualityChecks.check(code(files), CodeGenTypeEnum.VUE_PROJECT, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("scripts.build")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("vue 依赖")), errors.toString());
    }

    @Test
    void vueProjectChecksMissingModuleImports() {
        Map<String, String> files = new LinkedHashMap<>(vueProject());
        files.put("src/App.vue", "<script setup>\nimport Missing from './Missing.vue'\n</script>");

        List<String> errors = CodeQualityChecks.check(code(files), CodeGenTypeEnum.VUE_PROJECT, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("./Missing.vue")), errors.toString());
    }

    @Test
    void vueProjectResolvesExtensionlessImportsAndIndexFiles() {
        Map<String, String> files = new LinkedHashMap<>(vueProject());
        files.put("src/main.js", """
                import { createApp } from 'vue'
                import App from './App'
                import Home from '@/pages/Home'
                createApp(App).mount('#app')
                """);
        files.put("src/pages/Home/index.vue", "<template><div/></template>");
        files.put("vite.config.js", "export default { resolve: { alias: { '@': '/src' } } }");

        List<String> errors = CodeQualityChecks.check(code(files), CodeGenTypeEnum.VUE_PROJECT, 20);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void vueAliasWithoutViteConfigIsReported() {
        Map<String, String> files = new LinkedHashMap<>(vueProject());
        files.put("src/App.vue", "<script setup>\nimport Home from '@/pages/Home.vue'\n</script>");
        files.put("src/pages/Home.vue", "<template><div/></template>");

        List<String> errors = CodeQualityChecks.check(code(files), CodeGenTypeEnum.VUE_PROJECT, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("@ 路径别名")), errors.toString());
    }

    @Test
    void emptyCodeIsReported() {
        List<String> errors = CodeQualityChecks.check(code(Map.of()), CodeGenTypeEnum.HTML, 20);

        assertTrue(errors.stream().anyMatch(error -> error.contains("未找到可检查的代码文件")), errors.toString());
    }

    @Test
    void errorListIsCapped() {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 30; i++) {
            html.append("<img src=\"missing-").append(i).append(".png\">");
        }
        html.append("</body></html>");

        List<String> errors = CodeQualityChecks.check(code(Map.of("index.html", html.toString())),
                CodeGenTypeEnum.MULTI_FILE, 3);

        assertEquals(4, errors.size());
        assertTrue(errors.get(errors.size() - 1).contains("仅保留前 3 条"), errors.toString());
    }

    private static Map<String, String> vueProject() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("index.html", """
                <!DOCTYPE html><html><body><div id="app"></div>
                <script type="module" src="/src/main.js"></script></body></html>
                """);
        files.put("package.json", """
                {"name":"demo","scripts":{"build":"vite build"},
                 "dependencies":{"vue":"^3.3.4","vue-router":"^4.2.4"}}
                """);
        files.put("src/main.js", "import { createApp } from 'vue'\nimport App from './App.vue'\ncreateApp(App).mount('#app')");
        files.put("src/App.vue", "<template><div>ok</div></template>");
        return files;
    }

    private static ProjectCode code(Map<String, String> files) {
        List<ProjectCode.FileEntry> entries = new ArrayList<>();
        files.forEach((path, content) -> entries.add(ProjectCode.FileEntry.builder()
                .path(path)
                .content(content)
                .build()));
        return ProjectCode.builder()
                .files(entries)
                .text("")
                .build();
    }
}
