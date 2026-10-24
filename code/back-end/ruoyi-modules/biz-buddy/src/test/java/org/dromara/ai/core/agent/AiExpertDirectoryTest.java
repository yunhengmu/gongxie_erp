package org.dromara.ai.core.agent;

import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.mapper.AiExpertAgentMapper;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiExpertDirectory} 单元测试：候选/可见专家查询与实体转候选。
 */
@DisplayName("专家名录")
class AiExpertDirectoryTest {

    private final AiExpertAgentMapper mapper = mock(AiExpertAgentMapper.class);
    private final AiExpertDirectory directory = new AiExpertDirectory(mapper);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    private static AiExpertAgent agent(Long id, String code, String capability, String description) {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(id);
        agent.setAgentCode(code);
        agent.setAgentName("专家-" + id);
        agent.setCapabilityDesc(capability);
        agent.setDescription(description);
        return agent;
    }

    @Test
    @DisplayName("取自动候选清单")
    void shouldListCandidates() {
        when(mapper.selectList(any())).thenReturn(List.of(agent(1L, "k1", "能力", "描述")));
        List<AiExpertCandidate> candidates = directory.candidates(10L, 20L);
        assertEquals(1, candidates.size());
        assertEquals("k1", candidates.get(0).agentKey());
        assertEquals("能力", candidates.get(0).capabilityDesc());
    }

    @Test
    @DisplayName("能力描述为空回落职责说明")
    void shouldFallbackCapabilityToDescription() {
        when(mapper.selectList(any())).thenReturn(List.of(agent(1L, "k1", " ", "职责说明")));
        assertEquals("职责说明", directory.visibleExperts(10L, 20L).get(0).capabilityDesc());
    }

    @Test
    @DisplayName("按主键取可见专家")
    void shouldResolveVisibleExpert() {
        AiExpertAgent entity = agent(5L, "k5", "c", "d");
        when(mapper.selectOne(any())).thenReturn(entity);
        assertSame(entity, directory.visibleExpert(10L, 20L, 5L));
        assertEquals("k5", directory.visibleCandidate(10L, 20L, 5L).agentKey());
    }

    @Test
    @DisplayName("主键为空或不可见时返回空")
    void shouldReturnNullWhenNotVisible() {
        assertNull(directory.visibleExpert(10L, 20L, null));
        assertNull(directory.visibleCandidate(10L, 20L, null));
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(directory.visibleCandidate(10L, 20L, 99L));
    }

    @Test
    @DisplayName("取专家定义不校验可见性")
    void shouldResolveDefinition() {
        assertNull(directory.definition(null));
        AiExpertAgent entity = agent(7L, "k7", null, null);
        when(mapper.selectById(7L)).thenReturn(entity);
        assertSame(entity, directory.definition(7L));
    }
}