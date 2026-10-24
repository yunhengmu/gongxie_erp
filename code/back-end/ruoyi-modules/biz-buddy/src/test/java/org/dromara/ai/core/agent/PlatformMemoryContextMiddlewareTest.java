package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.memory.AiMemoryDeclaredStore;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台托管记忆注入：显式记忆优先、MEMORY.md 截断标记、非平台模式不注入。
 */
@DisplayName("平台记忆注入中间件")
class PlatformMemoryContextMiddlewareTest {

    @TempDir
    Path tempDir;

    private AiProperties aiProperties;

    private AiRuntimeConfigStore runtimeConfigStore;

    private AiMemoryDeclaredStore declaredStore;

    private final RuntimeContext ctx = RuntimeContext.builder().userId("7").build();

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getChat().setWorkspaceRoot(tempDir.toString());
        runtimeConfigStore = Mockito.mock(AiRuntimeConfigStore.class);
        declaredStore = new AiMemoryDeclaredStore(aiProperties);
    }

    @Test
    @DisplayName("显式记忆排在 MEMORY.md 之前")
    void shouldInjectDeclaredBeforeMemory() throws Exception {
        declaredStore.append("entry", "7", "用户喜欢用表格展示统计数据", "请记住");
        Path userDir = tempDir.resolve("entry").resolve("7");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), "用户偏好简洁回答", StandardCharsets.UTF_8);
        Mockito.when(runtimeConfigStore.memory()).thenReturn(policy("platform"));

        String prompt = middleware().onSystemPrompt(null, ctx, "BASE").block();

        assertTrue(prompt.contains("## 用户明确要求记住"));
        assertTrue(prompt.contains("用户喜欢用表格展示统计数据"));
        assertTrue(prompt.contains("## 长期记忆"));
        assertTrue(prompt.indexOf("## 用户明确要求记住") < prompt.indexOf("## 长期记忆"));
    }

    @Test
    @DisplayName("MEMORY.md 超预算追加截断标记")
    void shouldMarkTruncation() throws Exception {
        Path userDir = tempDir.resolve("entry").resolve("7");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), "记忆".repeat(2000), StandardCharsets.UTF_8);
        Mockito.when(runtimeConfigStore.memory()).thenReturn(policy("platform"));

        String prompt = middleware().onSystemPrompt(null, ctx, "BASE").block();

        assertTrue(prompt.contains("长期记忆已按预算截断"));
    }

    @Test
    @DisplayName("framework 模式不注入")
    void shouldNotInjectInFrameworkMode() {
        Mockito.when(runtimeConfigStore.memory()).thenReturn(policy("framework"));

        String prompt = middleware().onSystemPrompt(null, ctx, "BASE").block();

        assertEquals("BASE", prompt);
        assertFalse(prompt.contains("## 用户明确要求记住"));
    }

    private PlatformMemoryContextMiddleware middleware() {
        return new PlatformMemoryContextMiddleware(aiProperties, runtimeConfigStore, declaredStore, "entry");
    }

    private AiMemoryConfigBo policy(String mode) {
        AiMemoryConfigBo policy = new AiMemoryConfigBo();
        policy.setEnabled(true);
        policy.setPipelineMode(mode);
        policy.setMaxContextTokens(500);
        policy.setInjectDeclaredMaxTokens(100);
        return policy;
    }
}
