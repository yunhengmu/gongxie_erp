package org.dromara.ai.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
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
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import static org.mockito.Mockito.when;

/**
 * 编排链路的**贯通测试**：提问 → 路由 → 专家 → 写工具 → 确认 → 落库。
 *
 * <p>与既有的 {@link AiRouteOrchestratorTest}（各分支单测）互补：这里用**真实的**
 * {@link RouteDecisionTool} + {@link AiRouteOrchestrator} + {@link AiStreamEventBridge} 串起整条链路，
 * 只在最外层（模型 / 装配 / DB / Redis）打桩，验证「写操作被 HITL 闸门正确拦下、确认后才真正执行、
 * 且每一步都留痕可审计」这一条端到端语义不被后续重构破坏。</p>
 *
 * <p><b>覆盖边界</b>：模型侧用**事件流打桩**模拟专家行为（沿用框架真实事件类型），
 * 故本测试**不覆盖**「模型是否真的发出写工具调用」——那属于模型能力问题，需真实模型方可验证。</p>
 *
 * @author BizBuddy
 */
@DisplayName("编排链路贯通：提问→路由→专家→写工具→确认→落库")
class AiWriteChainTest {

    private static final Long ROUTE_RUN_ID = 1L;
    private static final Long SESSION_ID = 2L;
    private static final Long USER_ID = 3L;
    private static final Long ENTRY_AGENT_ID = 5L;
    private static final Long EXPERT_AGENT_ID = 10L;
    private static final String EXPERT_KEY = "notice_admin_expert";
    private static final String TOOL_CALL_ID = "call-1";
    private static final String FIRST_REPLY_ID = "reply-1";
    private static final String RESUME_REPLY_ID = "reply-2";

    private final AgentRegistry agentRegistry = mock(AgentRegistry.class);

    private final AiExpertDirectory expertDirectory = mock(AiExpertDirectory.class);

    private final AiRouteRecorder recorder = mock(AiRouteRecorder.class);

    private final IAiTraceService traceService = mock(IAiTraceService.class);

    private final IAiSkillService skillService = mock(IAiSkillService.class);

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final AiProperties aiProperties = new AiProperties();

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final AiSafetyGuard safetyGuard = mock(AiSafetyGuard.class);

    private final IAiSafetyEventService safetyEventService = mock(IAiSafetyEventService.class);

    private final SseEmitter emitter = mock(SseEmitter.class);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void stubBudget() {
        // 编排预算默认不限（0/0），不影响写工具链路断言
        lenient().when(runtimeConfigStore.routeBudget()).thenReturn(new AiRouteBudgetConfigBo());
    }

    /**
     * 写工具链路应贯通：路由命中写作专家 → 专家发起写调用被 HITL 拦下（确认卡片带出待执行调用）
     * → 用户确认 → 同一专家会话续跑并真正执行 → 专家调用与工具明细均留痕落库。
     */
    @Test
    @DisplayName("写工具被 HITL 拦下 → 确认后续跑执行 → 专家调用与工具明细留痕")
    void shouldCarryWriteToolThroughConfirmAndResume() throws Exception {
        // 装配真实编排器与真实路由工具（事件桥亦为真实实现）
        AiRouteOrchestrator orchestrator = new AiRouteOrchestrator(agentRegistry, expertDirectory, recorder,
            new AiStreamEventBridge(traceService, aiProperties, safetyGuard, safetyEventService,
                new org.dromara.ai.core.AiRetrievalSourceRegistry()), traceService,
            aiProperties, runtimeConfigStore, skillService, agentService);
        RouteDecisionTool routeTool = new RouteDecisionTool(orchestrator);

        AiExpertAgent definition = expertDefinition();
        HarnessAgent expert = stubExpertRun(definition);
        when(expertDirectory.definition(EXPERT_AGENT_ID)).thenReturn(definition);

        AiStreamState firstEntryState = new AiStreamState();
        AiRouteContext firstRoute = newRoute(firstEntryState);

        // ① 提问 → ② 路由：入口智能体（小Z）调用 route_decision，选中「公告管理专家」
        // ③ 专家发起写工具 create_notice，框架按「需确认」档暂停并上抛确认事件
        ToolUseBlock pendingWriteCall = new ToolUseBlock(TOOL_CALL_ID, "create_notice", noticeInput());
        when(expert.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.just(
            new ToolCallStartEvent(FIRST_REPLY_ID, TOOL_CALL_ID, "create_notice"),
            new RequireUserConfirmEvent(FIRST_REPLY_ID, List.of(pendingWriteCall))));

        ToolResultBlock result =
            routeTool.callAsync(param(RouteDecisionTool.TOOL_NAME, routeInput(), routeContext(firstRoute))).block();

        // 路由被受理：本轮暂停（不算失败），确认卡片带出**待执行**的写调用
        assertNotNull(result);
        assertEquals(AiRouteOrchestrator.PAUSE_TOOL_RESULT, text(result));
        assertNotNull(firstRoute.pendingResume, "命中「需确认」应上浮续跑信息");
        assertEquals(EXPERT_AGENT_ID, firstRoute.pendingResume.expertAgentId());
        assertEquals(ROUTE_RUN_ID + "#" + EXPERT_AGENT_ID, firstRoute.pendingResume.expertSessionId());
        assertFalse(firstRoute.degraded);
        assertTrue(firstEntryState.pendingConfirm, "入口轮应被标记为待确认，供门面落卡片");
        assertEquals(EXPERT_AGENT_ID, firstEntryState.pendingAgentId, "待确认归属应为专家，而非入口智能体");
        assertEquals(List.of("create_notice"),
            firstEntryState.pendingToolCalls.stream().map(AiConfirmPendingStore.PendingToolCall::toolName).toList());

        // 落库①：专家调用以 pending_confirm 记入 ai_route_expert_call
        ArgumentCaptor<AiRouteExpertCall> firstCall = ArgumentCaptor.forClass(AiRouteExpertCall.class);
        verify(recorder).recordExpertCall(firstCall.capture());
        assertEquals(AiRouteExpertCall.STATUS_PENDING_CONFIRM, firstCall.getValue().getStatus());
        assertEquals(AiRouteExpertCall.KIND_FIRST, firstCall.getValue().getCallKind());
        // 暂停时写工具**尚未执行**：明细已登记但无结果态
        assertTrue(firstRoute.expertToolLogs.stream()
            .anyMatch(log -> "create_notice".equals(log.getToolName()) && log.getState() == null));
        // 首段下发：route_decision + expert_start + answer_reset（暂停刻意不发 expert_result）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));

        // ④ 确认 → ⑤ 续跑：用 ConfirmResult 驱动**同一专家会话**续跑，写工具此时才真正执行
        // 恢复段与门面一致：重建编排上下文（复用同一次编排主键），候选清单按提问用户现算
        when(expert.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(
            new ToolCallStartEvent(RESUME_REPLY_ID, TOOL_CALL_ID, "create_notice"),
            new ToolResultEndEvent(RESUME_REPLY_ID, TOOL_CALL_ID, "create_notice", ToolResultState.SUCCESS),
            new TextBlockDeltaEvent(RESUME_REPLY_ID, "block-2", "公告已发布（通知 ID：1234），标题：关于10月8日放假的通知")));

        AiConfirmPendingStore.ExpertResume resume = firstRoute.pendingResume;
        AiStreamState resumeEntryState = new AiStreamState();
        AiRouteContext resumeRoute = newRoute(resumeEntryState);
        resumeRoute.resumed = true;

        AiRouteOrchestrator.ResumeOutcome outcome =
            orchestrator.resumeExpert(resumeRoute, resume, resumeMessages());

        assertFalse(outcome.paused(), "确认后应续跑完成，而非再次暂停");
        assertNotNull(outcome.report());
        assertFalse(resumeRoute.degraded);
        assertTrue(resumeRoute.handoff, "专家正文应接管交付");
        assertTrue(resumeRoute.handoffAnswer.toString().contains("公告已发布"));
        assertEquals(1, resumeRoute.expertCallCount.get(), "恢复段一次专家调用");

        // 落库②：续跑以 success 记入 ai_route_expert_call，且沿用同一专家会话、callKind=resume
        ArgumentCaptor<AiRouteExpertCall> allCalls = ArgumentCaptor.forClass(AiRouteExpertCall.class);
        verify(recorder, times(2)).recordExpertCall(allCalls.capture());
        AiRouteExpertCall resumed = allCalls.getAllValues().get(1);
        assertEquals(AiRouteExpertCall.STATUS_SUCCESS, resumed.getStatus());
        assertEquals(AiRouteExpertCall.KIND_RESUME, resumed.getCallKind());
        assertEquals(resume.expertSessionId(), resumed.getExpertSessionId(), "续跑必须复用同一专家会话");
        assertEquals(EXPERT_AGENT_ID, resumed.getExpertAgentId());
        // 写工具执行明细随编排上下文累积（收尾随助手消息一并落库，使专家写操作可审计）
        assertTrue(resumeRoute.expertToolLogs.stream()
                .anyMatch(log -> "create_notice".equals(log.getToolName())
                    && ToolResultState.SUCCESS.name().equals(log.getState())),
            "确认后写工具应真正执行并留下成功明细");
        // 恢复段累计下发 6 条（首段 3 + 恢复段 3：answer_reset + text_delta + expert_result）
        verify(emitter, times(6)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ==================== fixtures ====================

    /**
     * 公告管理专家定义：开启写工具开关（否则「需确认」档会被改写为拒绝，HITL 无从触发）。
     */
    private static AiExpertAgent expertDefinition() {
        AiExpertAgent definition = new AiExpertAgent();
        definition.setId(EXPERT_AGENT_ID);
        definition.setAgentCode(EXPERT_KEY);
        definition.setAgentName("公告管理专家");
        definition.setCapabilityDesc("公告维护");
        definition.setDescription("发布与维护系统公告");
        definition.setModelId(88L);
        definition.setExpertWriteEnabled(AiExpertAgent.WRITE_ENABLED);
        return definition;
    }

    /**
     * 构造携带编排上下文的路由工具入参（action=route，选中公告管理专家）。
     */
    private static Map<String, Object> routeInput() {
        return Map.of(
            "action", "route",
            "expert_agent_key", EXPERT_KEY,
            "task_context", Map.of("goal", "发布放假通知", "facts", Map.of("日期", "10月8日")),
            "confidence", 0.95);
    }

    /**
     * 写工具入参（发布公告）。
     */
    private static Map<String, Object> noticeInput() {
        return Map.of(
            "notice_title", "关于10月8日放假的通知",
            "notice_type", "1",
            "notice_content", "10月8日全公司继续放假一天。");
    }

    /**
     * 构造一次编排运行状态（主键与 {@link #stubExpertRun} 的槽位键对齐）。
     */
    private AiRouteContext newRoute(AiStreamState entryState) {
        AiExpertCandidate candidate =
            new AiExpertCandidate(EXPERT_AGENT_ID, EXPERT_KEY, "公告管理专家", null, "公告维护");
        AiRouteContext route = new AiRouteContext(ROUTE_RUN_ID, SESSION_ID, USER_ID, 4L, ENTRY_AGENT_ID,
            List.of(candidate), 6L, emitter);
        route.entryState = entryState;
        route.taskContext = "{\"goal\":\"发布放假通知\"}";
        return route;
    }

    /**
     * 构造携带编排上下文的运行期上下文（与真实链路一致：入口智能体把 route 放进 RuntimeContext）。
     */
    private static RuntimeContext routeContext(AiRouteContext route) {
        return RuntimeContext.builder().userId(String.valueOf(USER_ID))
            .put(AiRouteContext.class, route).build();
    }

    /**
     * 构造恢复消息（镜像 {@code AiChatFacade#buildResumeMessages}：ConfirmResult 随 user message 回传）。
     */
    private static List<Msg> resumeMessages() {
        ToolUseBlock block = new ToolUseBlock(TOOL_CALL_ID, "create_notice", noticeInput(),
            null, null, ToolCallState.ASKING);
        List<ConfirmResult> results = List.of(new ConfirmResult(true, block));
        Msg msg = Msg.builder()
            .role(MsgRole.USER)
            .textContent("用户已确认执行上述工具调用，请继续。")
            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
            .build();
        return List.of(msg);
    }

    /**
     * 打桩专家调用的装配期前置依赖（专家实例 / 权限基线 / 会话槽位 / 技能过滤 / 信任 / 子链路）。
     *
     * @param definition 专家定义（须与候选主键一致）
     * @return 专家实例（供用例配置 {@code streamEvents} 的事件流）
     */
    private HarnessAgent stubExpertRun(AiExpertAgent definition) {
        HarnessAgent expert = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(expert.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState(String.valueOf(USER_ID), ROUTE_RUN_ID + "#" + EXPERT_AGENT_ID))
            .thenReturn(agentState());

        when(agentRegistry.getExpert(eq(definition), any(AiSessionResourceSet.class))).thenReturn(expert);
        when(agentRegistry.permissionContextOfExpert(eq(definition), any(AiSessionResourceSet.class)))
            .thenReturn(PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build());
        when(skillService.resolveRuntimeSkillFilterKeys(anyLong(), any(), anyLong())).thenReturn(List.of());
        when(agentService.trustedToolCodes(EXPERT_AGENT_ID, USER_ID)).thenReturn(Set.of());
        when(traceService.start(anyLong(), anyLong(), anyLong(), anyString(), any(), any())).thenReturn(99L);
        return expert;
    }

    /**
     * 会话槽位状态（含权限上下文），供用户级信任合并读取。
     */
    private static AgentState agentState() {
        PermissionContextState context = PermissionContextState.builder()
            .mode(PermissionMode.DONT_ASK)
            .build();
        return AgentState.builder().permissionContext(context).build();
    }
}
