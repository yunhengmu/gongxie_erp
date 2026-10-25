package org.dromara.ai.core.memory;

import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.dromara.common.json.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * 平台抽取输出契约：needs_context / 低置信过滤、supersedes 保留、NO_REPLY 处理。
 */
@DisplayName("平台记忆抽取契约")
class AiMemoryExtractionServiceTest {

    private AiMemoryDeclaredStore declaredStore;

    private AiMemoryExtractionService service;

    private AiMessageMapper messageMapper;

    private AiSessionMapper sessionMapper;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUpClass() {
        org.dromara.ai.test.AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        declaredStore = Mockito.mock(AiMemoryDeclaredStore.class);
        Mockito.when(declaredStore.listActive(Mockito.anyString(), Mockito.anyString())).thenReturn(List.of());
        messageMapper = Mockito.mock(AiMessageMapper.class);
        sessionMapper = Mockito.mock(AiSessionMapper.class);
        service = new AiMemoryExtractionService(
            Mockito.mock(IAiAgentService.class),
            Mockito.mock(AgentRegistry.class),
            Mockito.mock(AiRuntimeConfigStore.class),
            new AiMemorySanitizer(),
            Mockito.mock(AiMemoryAuditLogger.class),
            declaredStore,
            Mockito.mock(IAiUsageStatService.class),
            sessionMapper,
            messageMapper);
    }

    @Test
    @DisplayName("清空记忆后写入截止消息 ID")
    void shouldMarkClearedWithMaxMessageId() throws Exception {
        AiMessage row = new AiMessage();
        row.setId(888L);
        Mockito.when(messageMapper.selectOne(ArgumentMatchers.any())).thenReturn(row);

        service.markCleared(tempDir);

        Map<String, Object> state = JsonUtils.parseMap(
            Files.readString(tempDir.resolve(".extract_state.json"), StandardCharsets.UTF_8));
        assertEquals("888", String.valueOf(state.get("clearedAtId")));
        assertTrue(((Map<?, ?>) state.get("sessions")).isEmpty());
    }

    @Test
    @DisplayName("清理过期会话水位并保留清空截止点")
    void shouldPruneExpiredWatermarks() throws Exception {
        AiSession alive = new AiSession();
        alive.setId(2L);
        Mockito.when(sessionMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(alive));
        Files.writeString(tempDir.resolve(".extract_state.json"),
            "{\"sessions\":{\"1\":10,\"2\":20},\"clearedAtId\":5}", StandardCharsets.UTF_8);

        int removed = service.pruneWatermarks(tempDir, 7L, LocalDate.now().minusDays(90));

        assertEquals(1, removed);
        Map<String, Object> state = JsonUtils.parseMap(
            Files.readString(tempDir.resolve(".extract_state.json"), StandardCharsets.UTF_8));
        Map<?, ?> sessions = (Map<?, ?>) state.get("sessions");
        assertFalse(sessions.containsKey("1"));
        assertTrue(sessions.containsKey("2"));
        assertEquals("5", String.valueOf(state.get("clearedAtId")));
    }

    @Test
    @DisplayName("过滤 needs_context 与低置信条目")
    void shouldDropNeedsContextAndLowConfidence() {
        AiMemoryExtractionService.FilterResult result = service.filterContract("""
            - [type=preference] 用户喜欢用表格展示统计数据
            - [needs_context] 用户说按这个格式，但上下文没有格式
            - [confidence=low] 用户可能喜欢卡片
            """, "entry", 7L);

        assertEquals(1, result.kept());
        assertEquals(1, result.needsContext());
        assertEquals(1, result.lowConfidence());
        assertTrue(result.content().contains("表格"));
        assertFalse(result.content().contains("卡片"));
    }

    @Test
    @DisplayName("supersedes 条目保留并供合并替换")
    void shouldKeepSupersedesLine() {
        AiMemoryExtractionService.FilterResult result = service.filterContract(
            "- [type=preference] [supersedes=表格] 用户改用卡片展示统计数据", "entry", 7L);

        assertEquals(1, result.kept());
        assertTrue(result.content().contains("supersedes=表格"));
        assertTrue(result.content().contains("卡片"));
    }

    @Test
    @DisplayName("NO_REPLY 不产生内容")
    void shouldHandleNoReply() {
        AiMemoryExtractionService.FilterResult result = service.filterContract("NO_REPLY", "entry", 7L);

        assertEquals(0, result.kept());
        assertTrue(result.content().isEmpty());
    }

    @Test
    @DisplayName("同会话抽取锁被占用时（零等待）放弃获取，不查会话与消息")
    void shouldSkipWhenSessionLockHeld() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(
                    ArgumentMatchers.startsWith("bizbuddy:ai:memory:extract:lock:"),
                    ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);

            assertFalse(service.acquireLock("bizbuddy:ai:memory:extract:lock:9", 0));
        }
    }

    @Test
    @DisplayName("抽取锁空闲时获取成功")
    void shouldAcquireLockWhenFree() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(
                    ArgumentMatchers.startsWith("bizbuddy:ai:memory:extract:lock:"),
                    ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(true);

            assertTrue(service.acquireLock("bizbuddy:ai:memory:extract:lock:9", 0));
        }
    }
}
