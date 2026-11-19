package com.zgq.zgqaicodemother.workflow.tools.mermaid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mermaid 代码安全校验测试，覆盖注入、外链、图表类型和长度限制。
 */
class MermaidCodeValidatorTest {

    @Test
    void acceptsValidFlowchartAndStripsFenceAndInit() {
        String code = """
                ```mermaid
                %%{init: {'theme': 'dark'}}%%
                flowchart LR
                  U[用户] --> F[前端]
                ```
                """;

        String validated = MermaidCodeValidator.validate(code, 2000, false);

        assertTrue(validated.startsWith("flowchart LR"), validated);
        assertFalse(validated.contains("init"));
        assertFalse(validated.contains("```"));
    }

    @Test
    void rejectsExternalResourcesByDefault() {
        assertThrows(IllegalArgumentException.class, () -> MermaidCodeValidator.validate(
                "flowchart LR\n  A --> B[\"https://evil.example.com\"]", 2000, false));
    }

    @Test
    void rejectsScriptAndDangerousDirectives() {
        assertThrows(IllegalArgumentException.class, () -> MermaidCodeValidator.validate(
                "flowchart LR\n  A[<script>alert(1)</script>] --> B", 2000, false));
        assertThrows(IllegalArgumentException.class, () -> MermaidCodeValidator.validate(
                "flowchart LR\n  A --> B\n  click A call callback()", 2000, false));
        String stripped = MermaidCodeValidator.validate(
                "%%{init: {'securityLevel': 'loose'}}%%\nflowchart LR\n A-->B", 2000, true);
        assertFalse(stripped.toLowerCase().contains("securitylevel"), stripped);
    }

    @Test
    void rejectsUnsupportedTypeAndEmptyCode() {
        assertThrows(IllegalArgumentException.class,
                () -> MermaidCodeValidator.validate("hello world", 2000, false));
        assertThrows(IllegalArgumentException.class,
                () -> MermaidCodeValidator.validate("   ", 2000, false));
        assertThrows(IllegalArgumentException.class,
                () -> MermaidCodeValidator.validate("flowchart LR\n A-->B", 10, false));
    }

    @Test
    void allowsExternalResourcesOnlyWhenExplicitlyEnabled() {
        String code = "flowchart LR\n  A --> B[\"https://cdn.example.com\"]";
        assertThrows(IllegalArgumentException.class,
                () -> MermaidCodeValidator.validate(code, 2000, false));
        String validated = MermaidCodeValidator.validate(code, 2000, true);
        assertTrue(validated.contains("cdn.example.com"));
    }
}
