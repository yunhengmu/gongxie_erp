package org.dromara.ai.core.memory;

import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiUsageStatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台抽取输出契约：needs_context / 低置信过滤、supersedes 保留、NO_REPLY 处理。
 */
@DisplayName("平台记忆抽取契约")
class AiMemoryExtractionServiceTest {

    private AiMemoryDeclaredStore declaredStore;

    private AiMemoryExtractionService service;

    @BeforeEach
    void setUp() {
        declaredStore = Mockito.mock(AiMemoryDeclaredStore.class);
        Mockito.when(declaredStore.listActive(Mockito.anyString(), Mockito.anyString())).thenReturn(List.of());
        service = new AiMemoryExtractionService(
            Mockito.mock(IAiAgentService.class),
            Mockito.mock(AgentRegistry.class),
            Mockito.mock(AiRuntimeConfigStore.class),
            new AiMemorySanitizer(),
            Mockito.mock(AiMemoryAuditLogger.class),
            declaredStore,
            Mockito.mock(IAiUsageStatService.class),
            Mockito.mock(AiSessionMapper.class),
            Mockito.mock(AiMessageMapper.class));
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
}
