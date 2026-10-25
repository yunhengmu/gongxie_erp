package org.dromara.ai.core.agent;

import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiRouteContext} 单元测试：候选索引、指定专家解析与候选清单渲染。
 */
@DisplayName("编排运行共享状态")
class AiRouteContextTest {

    private static AiExpertCandidate candidate(Long id, String key, String name) {
        return new AiExpertCandidate(id, key, name, "能力");
    }

    private AiRouteContext newContext(List<AiExpertCandidate> candidates) {
        return new AiRouteContext(1L, 2L, 3L, 4L, 5L, candidates, 6L, null);
    }

    @Test
    @DisplayName("按 key 取候选专家")
    void shouldResolveCandidateByKey() {
        AiRouteContext ctx = newContext(List.of(candidate(10L, "k1", "专家一"), candidate(11L, null, "无键")));
        assertSame("专家一", ctx.candidate("k1").agentName());
        assertNull(ctx.candidate(null));
        assertNull(ctx.candidate("missing"));
        // agentKey 为空的候选不进索引
        assertNull(ctx.candidate(""));
    }

    @Test
    @DisplayName("指定专家优先取候选视图，否则按主键匹配")
    void shouldResolvePinnedCandidate() {
        AiRouteContext ctx = newContext(List.of(candidate(10L, "k1", "专家一"), candidate(11L, "k2", "专家二")));

        assertNull(ctx.pinnedCandidate());

        ctx.pinnedExpertId = 11L;
        assertEquals("专家二", ctx.pinnedCandidate().agentName());

        AiExpertCandidate pinned = candidate(20L, "k9", "指定专家");
        ctx.pinnedExpert = pinned;
        assertSame(pinned, ctx.pinnedCandidate());

        // 主键不在候选清单中
        AiRouteContext other = newContext(List.of(candidate(10L, "k1", "专家一")));
        other.pinnedExpertId = 99L;
        assertNull(other.pinnedCandidate());
    }

    @Test
    @DisplayName("渲染候选清单")
    void shouldRenderCandidates() {
        AiRouteContext empty = newContext(List.of());
        assertTrue(empty.renderCandidates().contains("没有任何可用的专家"));

        AiRouteContext ctx = newContext(List.of(candidate(10L, "k1", "专家一")));
        String rendered = ctx.renderCandidates();
        assertTrue(rendered.contains("- key=k1"));
        assertTrue(rendered.contains("名称=专家一"));
        assertTrue(rendered.endsWith("\n"));
    }

    @Test
    @DisplayName("构造时初始化默认资源集与计数器")
    void shouldInitDefaults() {
        AiRouteContext ctx = newContext(List.of());
        assertSame(AiSessionResourceSet.empty().getClass(), ctx.resources.getClass());
        assertEquals(0, ctx.expertCallCount.get());
        assertEquals(0L, ctx.expertPromptTokens.get());
        assertEquals(0L, ctx.expertCompletionTokens.get());
        assertTrue(ctx.expertToolLogs.isEmpty());
    }
}