package org.dromara.ai.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.bo.AiRouteBudgetConfigBo;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiRouteOrchestrator} 单元测试（第 1 圈：只覆盖调用真实 agent 之前/之外的「降级骨架」分支）。
 *
 * <p>不触碰 AgentScope 运行时（{@code runOnce} / {@code runExpert} 的真实流式执行不在本圈范围），
 * 故 {@code agentRegistry} / {@code recorder} / {@code streamBridge} 等仅作构造注入的 mock。</p>
 */
@DisplayName("专家编排器（降级骨架）")
class AiRouteOrchestratorTest {

    private final AgentRegistry agentRegistry = mock(AgentRegistry.class);

    private final AiExpertDirectory expertDirectory = mock(AiExpertDirectory.class);

    private final AiRouteRecorder recorder = mock(AiRouteRecorder.class);

    private final AiStreamEventBridge streamBridge = mock(AiStreamEventBridge.class);

    private final IAiTraceService traceService = mock(IAiTraceService.class);

    private final AiProperties aiProperties = new AiProperties();

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final AiSafetyGuard safetyGuard = mock(AiSafetyGuard.class);

    private final IAiSafetyEventService safetyEventService = mock(IAiSafetyEventService.class);

    private final IAiSkillService skillService = mock(IAiSkillService.class);

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final SseEmitter emitter = mock(SseEmitter.class);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void stubBudget() {
        // 编排预算默认不限（0/0）；按需在用例内覆盖
        lenient().when(runtimeConfigStore.routeBudget()).thenReturn(new AiRouteBudgetConfigBo());
    }

    private AiRouteOrchestrator newOrchestrator() {
        return new AiRouteOrchestrator(agentRegistry, expertDirectory, recorder, streamBridge,
            traceService, aiProperties, runtimeConfigStore, skillService, agentService);
    }

    private AiRouteContext newRoute() {
        return new AiRouteContext(1L, 2L, 3L, 4L, 5L, List.of(), 6L, emitter);
    }

    private static AiExpertCandidate candidate() {
        return new AiExpertCandidate(10L, "k1", "专家一", null, "能力");
    }

    @Test
    @DisplayName("指定专家定义缺失时直接降级并下发 fallback 事件")
    void shouldDegradeWhenPinnedExpertMissing() throws Exception {
        AiRouteContext route = newRoute();
        route.pinnedExpertId = 10L;
        // expertDirectory.definition(10L) 未打桩，默认返回 null

        String report = newOrchestrator().executePinned(route);

        assertTrue(route.degraded);
        assertEquals("指定专家不存在或已被删除", route.degradeReason);
        assertTrue(report.startsWith("【专家结论不可用】"));
        assertTrue(report.contains("编排侧原因：指定专家不存在或已被删除"));
        // 仅 EVENT_ROUTE_FALLBACK 一条
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        // 降级在调用真实 agent 之前返回，不应有任何专家调用落库
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("预算触顶时在调用专家前降级")
    void shouldDegradeOnBudgetExceeded() throws Exception {
        AiRouteBudgetConfigBo budget = new AiRouteBudgetConfigBo();
        budget.setExpertTokens(100);
        when(runtimeConfigStore.routeBudget()).thenReturn(budget);
        AiRouteContext route = newRoute();
        route.budgetExceeded = true;
        route.expertPromptTokens.addAndGet(150);
        AiExpertCandidate chosen = candidate();

        String report = newOrchestrator().execute(route, chosen);

        assertTrue(route.degraded);
        assertTrue(route.degradeReason.startsWith("超出编排预算"));
        assertTrue(route.degradeReason.contains("专家轮 Token 累计 150 ≥ 上限 100"));
        assertTrue(report.contains("【专家结论不可用】"));
        assertTrue(report.contains("已尝试专家：专家一（k1）"));
        assertTrue(report.contains("超出编排预算"));
        // route_decision + route_fallback 两条事件
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        // 预算分支在 runOnce 之前返回，不触发专家调用
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("续跑时专家定义缺失直接降级")
    void shouldDegradeResumeWhenExpertMissing() throws Exception {
        AiRouteContext route = newRoute();
        AiConfirmPendingStore.ExpertResume resume =
            new AiConfirmPendingStore.ExpertResume(1L, 10L, "1#10", "task", 1, "first");
        // expertDirectory.definition(10L) 默认返回 null

        AiRouteOrchestrator.ResumeOutcome outcome =
            newOrchestrator().resumeExpert(route, resume, List.of());

        assertFalse(outcome.paused());
        assertNotNull(outcome.report());
        assertTrue(route.degraded);
        assertEquals("专家不存在或已被删除，无法续跑", route.degradeReason);
        assertTrue(outcome.report().startsWith("【专家结论不可用】"));
        assertTrue(outcome.report().contains("编排侧原因：专家不存在或已被删除，无法续跑"));
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verifyNoInteractions(recorder);
    }

    // ==================== 第 2 圈：runOnce / runExpert 正常路径 ====================

    /**
     * 用**真实** {@link AiStreamEventBridge} 构造编排器：专家事件经真实桥接器分发，
     * 从而驱动 {@code state} 累积（正文 / 待确认），覆盖 {@code runExpert} 的主流程。
     */
    private AiRouteOrchestrator newOrchestratorWithRealBridge() {
        return new AiRouteOrchestrator(agentRegistry, expertDirectory, recorder,
            new AiStreamEventBridge(traceService, aiProperties, safetyGuard, safetyEventService), traceService,
            aiProperties, runtimeConfigStore, skillService, agentService);
    }

    /** 专家定义（主键 10，与 {@link #candidate()} 对齐）。 */
    private static AiExpertAgent expertDefinition() {
        AiExpertAgent definition = new AiExpertAgent();
        definition.setId(10L);
        definition.setAgentCode("k1");
        definition.setAgentName("专家一");
        definition.setCapabilityDesc("能力");
        definition.setDescription("一句话职责");
        definition.setModelId(88L);
        // expertWriteEnabled 未设 → expertWriteAllowed()=false，走「ask 改写为 deny」分支
        return definition;
    }

    /** 会话槽位状态（含权限上下文），供 applyUserToolTrust / denyAskingTools 读取。 */
    private static AgentState agentState() {
        PermissionContextState context = PermissionContextState.builder()
            .mode(PermissionMode.DONT_ASK)
            .build();
        return AgentState.builder().permissionContext(context).build();
    }

    /**
     * 打桩 {@code runExpert} 的装配期前置依赖（专家实例 / 权限基线 / 会话槽位 / 技能过滤 / 信任查询 / 子链路）。
     *
     * @param definition 专家定义（须与 {@code candidate().agentId()} 一致）
     * @return 专家实例（供各用例配置 {@code streamEvents} 返回的事件流）
     */
    private HarnessAgent stubExpertRun(AiExpertAgent definition) {
        HarnessAgent expert = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(expert.getDelegate()).thenReturn(delegate);
        // route.userId=3L、expertSessionId=routeRunId#agentId="1#10"
        when(delegate.getAgentState("3", "1#10")).thenReturn(agentState());

        when(agentRegistry.getExpert(eq(definition), any(AiSessionResourceSet.class))).thenReturn(expert);
        when(agentRegistry.permissionContextOfExpert(eq(definition), any(AiSessionResourceSet.class)))
            .thenReturn(PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build());
        when(skillService.resolveRuntimeSkillFilterKeys(anyLong(), any(), anyLong())).thenReturn(List.of());
        when(agentService.trustedToolCodes(10L, 3L)).thenReturn(Set.of());
        when(traceService.start(anyLong(), anyLong(), anyLong(), anyString(), any(), any())).thenReturn(99L);
        return expert;
    }

    @Test
    @DisplayName("execute 正常路径：专家正文直传并落库（不降级）")
    void shouldExecuteHappyPath() throws Exception {
        AiExpertAgent definition = expertDefinition();
        HarnessAgent expert = stubExpertRun(definition);
        when(expertDirectory.definition(10L)).thenReturn(definition);
        when(expert.streamEvents(any(Msg.class), any(RuntimeContext.class)))
            .thenReturn(Flux.<AgentEvent>just(new TextBlockDeltaEvent("reply-1", "block-1", "专家回答")));
        AiRouteContext route = newRoute();

        String report = newOrchestratorWithRealBridge().execute(route, candidate());

        assertFalse(route.degraded);
        assertTrue(route.handoff);
        assertEquals("专家回答", route.handoffAnswer.toString());
        assertEquals(1, route.expertCallCount.get());
        assertTrue(report.contains("专家已直接回答用户"));
        verify(recorder).recordExpertCall(any(AiRouteExpertCall.class));
        // route_decision + expert_start + answer_reset + text_delta + expert_result
        verify(emitter, times(5)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("executePinned 正常路径：定义存在时走 runExpert，无正文则降级")
    void shouldExecutePinnedThroughRunExpertAndDegradeOnEmptyOutput() throws Exception {
        AiExpertAgent definition = expertDefinition();
        HarnessAgent expert = stubExpertRun(definition);
        when(expertDirectory.definition(10L)).thenReturn(definition);
        when(expert.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>empty());
        AiRouteContext route = newRoute();
        route.pinnedExpertId = 10L;

        String report = newOrchestratorWithRealBridge().executePinned(route);

        assertTrue(route.degraded);
        assertEquals("专家结论不可用：专家未产出任何内容", route.degradeReason);
        assertTrue(report.contains("【专家结论不可用】"));
        assertTrue(report.contains("已尝试专家：专家一（k1）"));
        verify(recorder).recordExpertCall(any(AiRouteExpertCall.class));
        // route_decision + expert_start + answer_reset + expert_result + route_fallback
        verify(emitter, times(5)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("execute 命中「需确认」：暂停并上浮 pendingResume")
    void shouldPauseWhenExpertRequiresConfirm() throws Exception {
        AiExpertAgent definition = expertDefinition();
        HarnessAgent expert = stubExpertRun(definition);
        when(expertDirectory.definition(10L)).thenReturn(definition);
        when(expert.streamEvents(any(Msg.class), any(RuntimeContext.class)))
            .thenReturn(Flux.<AgentEvent>just(new RequireUserConfirmEvent("reply-1", null)));
        AiRouteContext route = newRoute();

        String report = newOrchestratorWithRealBridge().execute(route, candidate());

        assertEquals(AiRouteOrchestrator.PAUSE_TOOL_RESULT, report);
        assertNotNull(route.pendingResume);
        assertEquals(10L, route.pendingResume.expertAgentId().longValue());
        assertFalse(route.degraded);
        verify(recorder).recordExpertCall(any(AiRouteExpertCall.class));
        // route_decision + expert_start + answer_reset（暂停刻意不下发 expert_result）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("resumeExpert 正常路径：续跑成功返回非 paused 结果")
    void shouldResumeExpertSuccess() throws Exception {
        AiExpertAgent definition = expertDefinition();
        HarnessAgent expert = stubExpertRun(definition);
        when(expertDirectory.definition(10L)).thenReturn(definition);
        when(expert.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.<AgentEvent>just(new TextBlockDeltaEvent("reply-2", "block-2", "续跑回答")));
        AiRouteContext route = newRoute();
        AiConfirmPendingStore.ExpertResume resume =
            new AiConfirmPendingStore.ExpertResume(1L, 10L, "1#10", "task", 2, "resume");

        AiRouteOrchestrator.ResumeOutcome outcome =
            newOrchestratorWithRealBridge().resumeExpert(route, resume, List.of());

        assertFalse(outcome.paused());
        assertNotNull(outcome.report());
        assertTrue(outcome.report().contains("专家已直接回答用户"));
        assertEquals("续跑回答", route.handoffAnswer.toString());
        assertFalse(route.degraded);
        verify(recorder).recordExpertCall(any(AiRouteExpertCall.class));
        // answer_reset + text_delta + expert_result（续跑不发 expert_start）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("resumeExpert 再次命中「需确认」：返回 paused 且 report 为 null")
    void shouldResumeExpertPausedAgain() throws Exception {
        AiExpertAgent definition = expertDefinition();
        HarnessAgent expert = stubExpertRun(definition);
        when(expertDirectory.definition(10L)).thenReturn(definition);
        when(expert.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.<AgentEvent>just(new RequireUserConfirmEvent("reply-2", null)));
        AiRouteContext route = newRoute();
        AiConfirmPendingStore.ExpertResume resume =
            new AiConfirmPendingStore.ExpertResume(1L, 10L, "1#10", "task", 1, "first");

        AiRouteOrchestrator.ResumeOutcome outcome =
            newOrchestratorWithRealBridge().resumeExpert(route, resume, List.of());

        assertTrue(outcome.paused());
        assertNull(outcome.report());
        assertNotNull(route.pendingResume);
        verify(recorder).recordExpertCall(any(AiRouteExpertCall.class));
        // 仅 answer_reset（暂停刻意不下发 expert_result）
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
