package org.dromara.ai.core.agent;

import io.agentscope.core.skill.SkillFilter;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiRouteSupport} 的纯单元测试（不碰运行时 / 数据库）。
 *
 * <p>覆盖：链路来源推导、场景包解析与技能主键提取的空值短路、技能白名单两支（资源集 / 场景包）
 * 的委托参数，以及 {@code task_context} 的逐字拼装。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("编排辅助")
class AiRouteSupportTest {

    @Mock
    private IAiSkillService skillService;

    @Mock
    private IAiScenarioPackService scenarioPackService;

    private AiRouteSupport support;

    @BeforeEach
    void setUp() {
        support = new AiRouteSupport(skillService, scenarioPackService);
    }

    @Test
    @DisplayName("链路来源：渠道来源映射为 channel")
    void shouldResolveChannelTraceSource() {
        AiSession session = new AiSession();
        session.setSource(AiSession.SOURCE_CHANNEL);

        assertEquals(AiTrace.SOURCE_CHANNEL, AiRouteSupport.resolveTraceSource(session));
    }

    @Test
    @DisplayName("链路来源：其它或空来源均回落 task")
    void shouldResolveTaskTraceSourceForNonChannel() {
        AiSession other = new AiSession();
        other.setSource("1");
        assertEquals(AiTrace.SOURCE_TASK, AiRouteSupport.resolveTraceSource(other));

        AiSession blank = new AiSession();
        assertEquals(AiTrace.SOURCE_TASK, AiRouteSupport.resolveTraceSource(blank));
    }

    @Test
    @DisplayName("场景包：会话为空直接返回 null 且不查库")
    void shouldReturnNullPackWhenSessionNull() {
        assertNull(support.resolveScenarioPack(null));
        verifyNoInteractions(scenarioPackService);
    }

    @Test
    @DisplayName("场景包：按会话固化的包主键委托查询")
    void shouldDelegateEnabledPackLookup() {
        AiSession session = new AiSession();
        session.setScenarioPackId(9L);
        AiScenarioPack pack = new AiScenarioPack();
        when(scenarioPackService.getEnabledPackOrNull(9L)).thenReturn(pack);

        assertEquals(pack, support.resolveScenarioPack(session));
    }

    @Test
    @DisplayName("技能主键：未选包返回空列表且不查库")
    void shouldReturnEmptySkillIdsWhenPackNull() {
        assertEquals(List.of(), support.packSkillIds(null));
        verifyNoInteractions(scenarioPackService);
    }

    @Test
    @DisplayName("技能主键：按包主键委托查询")
    void shouldDelegatePackSkillIds() {
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(5L);
        when(scenarioPackService.queryPackSkillIds(5L)).thenReturn(List.of(1L, 2L));

        assertEquals(List.of(1L, 2L), support.packSkillIds(pack));
    }

    @Test
    @DisplayName("技能白名单：无命中返回 none，资源集为空时按空技能集合委托")
    void shouldReturnNoneFilterWhenNoSkills() {
        when(skillService.resolveRuntimeSkillFilterKeys(1L, List.of(), 2L)).thenReturn(List.of());

        SkillFilter filter = support.runtimeSkillFilter(1L, (AiSessionResourceSet) null, 2L);

        assertNotNull(filter);
    }

    @Test
    @DisplayName("技能白名单：有命中返回 only（场景包路径按空技能集合委托）")
    void shouldReturnOnlyFilterWhenSkillsPresent() {
        when(skillService.resolveRuntimeSkillFilterKeys(1L, List.of(), 2L)).thenReturn(List.of("s1"));

        SkillFilter filter = support.runtimeSkillFilter(1L, (AiScenarioPack) null, 2L);

        assertNotNull(filter);
    }

    @Test
    @DisplayName("task_context：空问题与空槽位、无部门时的逐字拼装")
    void shouldRenderTaskContext() {
        String text = AiRouteSupport.taskContext(null, null, 4L, null);

        assertEquals("【用户原话】\n\n\n【上下文】提问用户ID=4", text);
    }

    @Test
    @DisplayName("task_context：带槽位与部门时追加对应段落")
    void shouldRenderTaskContextWithSlotsAndDept() {
        AiSlotSet slots = AiSlotSet.empty();

        String text = AiRouteSupport.taskContext("帮我发通知", slots, 4L, 6L);

        assertTrue(text.startsWith("【用户原话】\n帮我发通知\n"), text);
        assertTrue(text.contains("【上下文】提问用户ID=4；部门ID=6"), text);
    }
}
