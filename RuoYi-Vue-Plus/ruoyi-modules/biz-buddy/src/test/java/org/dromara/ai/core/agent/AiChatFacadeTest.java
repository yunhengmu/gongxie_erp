package org.dromara.ai.core.agent;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiChatConfirmBo;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.vo.AiChatPendingVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.ai.service.IAiQuotaService;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatFacade} 的纯单元测试（覆盖 {@code listPending} / {@code confirm}，以及
 * {@code streamChat} / {@code streamResume} 的前置校验与确定性事件流终态分支）。
 *
 * <p>不触碰 AgentScope 运行时 / 真实模型 / Redis / DB；事件流终态用 {@code Flux.empty()} /
 * {@code Flux.error(...)} 在订阅线程同步驱动，全程 Mockito + JUnit5，无 Spring 容器、无 sleep。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("对话门面：待确认查询、处置与流式分支")
class AiChatFacadeTest {

    private static final Long SESSION_ID = 1L;
    private static final Long MESSAGE_ID = 2L;
    private static final int TTL_MINUTES = 30;

    @Mock
    private IAiSessionService sessionService;
    @Mock
    private IAiAgentService agentService;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AgentRegistry agentRegistry;
    @Mock
    private AiProperties aiProperties;
    @Mock
    private AiConfirmPendingStore pendingStore;
    @Mock
    private AiPermissionModeStore permissionModeStore;
    @Mock
    private IAiPermissionDecisionService decisionService;
    @Mock
    private IAiUsageStatService usageStatService;
    @Mock
    private IAiQuotaService quotaService;
    @Mock
    private IAiSkillService skillService;
    @Mock
    private IAiMemoryService memoryService;
    @Mock
    private IAiScenarioPackService scenarioPackService;
    @Mock
    private IAiSessionResourceService sessionResourceService;
    @Mock
    private AiExpertDirectory expertDirectory;
    @Mock
    private AiRouteRecorder routeRecorder;
    @Mock
    private AiSlotStore slotStore;
    @Mock
    private AiRouteOrchestrator routeOrchestrator;
    @Mock
    private AiStreamEventBridge streamBridge;
    @Mock
    private IAiTraceService traceService;
    @Mock
    private IAiChatAttachmentService attachmentService;

    @Mock
    private AiRunRegistry runRegistry;

    private AiChatFacade facade;

    /**
     * 显式构造门面：门面已按职责拆出协作类（附件装配等），{@code @InjectMocks} 无法为具体类注入。
     * 拆出的类用**真实实例 + 共享同一批 {@code @Mock}**，故原有 verify 断言不受影响。
     */
    @BeforeEach
    void setUp() {
        AiAttachmentAssembler assembler = new AiAttachmentAssembler(attachmentService, aiProperties);
        AiRouteSupport routeSupport = new AiRouteSupport(skillService, scenarioPackService);
        AiTrustApplier trustApplier = new AiTrustApplier(agentRegistry, agentService);
        AiTurnFinisher turnFinisher = new AiTurnFinisher(sessionService, decisionService, usageStatService,
            traceService, memoryService, aiProperties, routeRecorder, pendingStore);
        AiIntegrationDriver integrationDriver = new AiIntegrationDriver(sessionService, pendingStore, traceService,
            routeOrchestrator, streamBridge, runRegistry, turnFinisher);
        AiExpertResumeDriver expertResumeDriver = new AiExpertResumeDriver(sessionService, agentService, agentRegistry,
            pendingStore, traceService, sessionResourceService, expertDirectory, slotStore, permissionModeStore,
            routeOrchestrator, runRegistry, turnFinisher, integrationDriver, routeSupport, trustApplier);
        facade = new AiChatFacade(sessionService, agentService, messageMapper, agentRegistry, aiProperties,
            pendingStore, permissionModeStore, decisionService, usageStatService, quotaService,
            sessionResourceService, expertDirectory, routeRecorder, routeOrchestrator, assembler, routeSupport, trustApplier,
            slotStore, streamBridge, traceService, runRegistry, turnFinisher, integrationDriver, expertResumeDriver);
    }

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    // ---------------- listPending ----------------

    @Test
    @DisplayName("命中暂存：expired=false 且逐项映射工具调用与有效期")
    void shouldMapPendingToolCalls() {
        when(aiProperties.getChat()).thenReturn(chatWithTtl());
        when(sessionService.queryPendingMessages(SESSION_ID)).thenReturn(List.of(message(MESSAGE_ID)));
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(pending(twoCalls(), null)));

        List<AiChatPendingVo> result = facade.listPending(SESSION_ID);

        assertEquals(1, result.size());
        AiChatPendingVo vo = result.get(0);
        assertEquals(MESSAGE_ID, vo.getMessageId());
        assertFalse(vo.getExpired());
        assertEquals((long) TTL_MINUTES * 60, vo.getExpiresInSec().longValue());
        assertEquals("reply-1", vo.getReplyId());
        assertEquals(2, vo.getToolCalls().size());
        assertEquals("c1", vo.getToolCalls().get(0).getToolCallId());
        assertEquals("create_notice", vo.getToolCalls().get(0).getToolName());
        assertEquals(Map.of("a", 1), vo.getToolCalls().get(0).getInput());
        assertEquals("c2", vo.getToolCalls().get(1).getToolCallId());
        assertEquals("delete_user", vo.getToolCalls().get(1).getToolName());
    }

    @Test
    @DisplayName("暂存缺失：expired=true 且工具调用为空列表")
    void shouldMarkExpiredWhenPendingAbsent() {
        when(aiProperties.getChat()).thenReturn(chatWithTtl());
        when(sessionService.queryPendingMessages(SESSION_ID)).thenReturn(List.of(message(MESSAGE_ID)));
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.empty());

        List<AiChatPendingVo> result = facade.listPending(SESSION_ID);

        assertEquals(1, result.size());
        AiChatPendingVo vo = result.get(0);
        assertTrue(vo.getExpired());
        assertTrue(vo.getToolCalls().isEmpty());
        assertNull(vo.getReplyId());
        assertEquals((long) TTL_MINUTES * 60, vo.getExpiresInSec().longValue());
    }

    @Test
    @DisplayName("无待确认消息：返回空列表")
    void shouldReturnEmptyWhenNoPendingMessages() {
        when(aiProperties.getChat()).thenReturn(chatWithTtl());
        when(sessionService.queryPendingMessages(SESSION_ID)).thenReturn(List.of());

        assertTrue(facade.listPending(SESSION_ID).isEmpty());
    }

    @Test
    @DisplayName("多条消息混合命中与缺失")
    void shouldHandleMixedMessages() {
        when(aiProperties.getChat()).thenReturn(chatWithTtl());
        when(sessionService.queryPendingMessages(SESSION_ID)).thenReturn(List.of(message(MESSAGE_ID), message(3L)));
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(pending(twoCalls(), null)));
        when(pendingStore.get(SESSION_ID, 3L)).thenReturn(Optional.empty());

        List<AiChatPendingVo> result = facade.listPending(SESSION_ID);

        assertEquals(2, result.size());
        assertFalse(result.get(0).getExpired());
        assertTrue(result.get(1).getExpired());
    }

    // ---------------- confirm ----------------

    @Test
    @DisplayName("暂存缺失：抛出业务异常")
    void shouldThrowWhenConfirmPendingAbsent() {
        AiChatConfirmBo bo = confirmBo(MESSAGE_ID, true, false);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class, () -> facade.confirm(SESSION_ID, bo));
        assertEquals("确认已过期或已被处理，请重新提问", ex.getMessage());
    }

    @Test
    @DisplayName("确认且不记住：回填 confirmed 决策，不写信任")
    void shouldRecordConfirmedDecisionsWithoutRemember() {
        AiChatConfirmBo bo = confirmBo(MESSAGE_ID, true, false);
        AiConfirmPendingStore.PendingConfirm p = pending(twoCalls(), null);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(p));

        facade.confirm(SESSION_ID, bo);

        verify(pendingStore).save(withDecisions(p, true, false, null));
        verify(decisionService).applyConfirm(MESSAGE_ID, true, false, null);
        verify(agentService, never()).rememberToolForAgent(any(), any(), any());
    }

    @Test
    @DisplayName("拒绝且带记住位：决策取拒绝，仍不写信任")
    void shouldRecordRejectedDecisions() {
        AiChatConfirmBo bo = confirmBo(MESSAGE_ID, false, true);
        AiConfirmPendingStore.PendingConfirm p = pending(twoCalls(), null);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(p));

        facade.confirm(SESSION_ID, bo);

        verify(pendingStore).save(withDecisions(p, false, true, null));
        verify(decisionService).applyConfirm(MESSAGE_ID, false, true, null);
        verify(agentService, never()).rememberToolForAgent(any(), any(), any());
    }

    @Test
    @DisplayName("拒绝并带原因：原因随决策暂存并写入决策日志")
    void shouldRecordRejectionReason() {
        AiChatConfirmBo bo = confirmBo(MESSAGE_ID, false, false);
        bo.setRejectReason("  数据敏感，暂不执行  ");
        AiConfirmPendingStore.PendingConfirm p = pending(twoCalls(), null);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(p));

        facade.confirm(SESSION_ID, bo);

        verify(pendingStore).save(withDecisions(p, false, false, "数据敏感，暂不执行"));
        verify(decisionService).applyConfirm(MESSAGE_ID, false, false, "数据敏感，暂不执行");
    }

    @Test
    @DisplayName("确认并记住：入口链路写信任并落入入口装配")
    void shouldRememberToolsAndApplyEntryTrust() {
        AiAgent definition = mock(AiAgent.class);
        AiSessionResourceSet resources = AiSessionResourceSet.empty();
        AiChatConfirmBo bo = confirmBo(MESSAGE_ID, true, true);
        AiConfirmPendingStore.PendingConfirm p = pending(twoCalls(), null);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(p));
        when(agentService.getEnabledAgent(7L)).thenReturn(definition);
        when(sessionResourceService.resolveForSession(any())).thenReturn(resources);

        facade.confirm(SESSION_ID, bo);

        verify(agentService).rememberToolForAgent(7L, 4L, "create_notice");
        verify(agentService).rememberToolForAgent(7L, 4L, "delete_user");
        verify(agentRegistry).get(definition, resources);
        verify(agentRegistry, never()).getExpert(definition, resources);
        verify(pendingStore).save(withDecisions(p, true, true, null));
        verify(decisionService).applyConfirm(MESSAGE_ID, true, true, null);
    }

    @Test
    @DisplayName("确认并记住：专家链路写信任并落入专家装配")
    void shouldRememberToolsAndApplyExpertTrust() {
        AiAgent definition = mock(AiAgent.class);
        AiSessionResourceSet resources = AiSessionResourceSet.empty();
        AiConfirmPendingStore.ExpertResume resume =
            new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first");
        AiChatConfirmBo bo = confirmBo(MESSAGE_ID, true, true);
        AiConfirmPendingStore.PendingConfirm p = pending(twoCalls(), resume);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(p));
        when(agentService.getEnabledAgent(7L)).thenReturn(definition);
        when(sessionResourceService.resolveForSession(any())).thenReturn(resources);

        facade.confirm(SESSION_ID, bo);

        verify(agentService).rememberToolForAgent(7L, 4L, "create_notice");
        verify(agentService).rememberToolForAgent(7L, 4L, "delete_user");
        verify(agentRegistry).getExpert(definition, resources);
        verify(agentRegistry, never()).get(definition, resources);
        verify(pendingStore).save(withDecisions(p, true, true, null));
        verify(decisionService).applyConfirm(MESSAGE_ID, true, true, null);
    }

    // ---------------- stop ----------------

    @Test
    @DisplayName("stop：校验会话归属后取消当前执行")
    void shouldCancelRunningExecution() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(runRegistry.cancel(SESSION_ID)).thenReturn(true);

        facade.stop(SESSION_ID);

        verify(runRegistry).cancel(SESSION_ID);
    }

    // ---------------- streamChat：前置校验 / 早退 ----------------

    @Test
    @DisplayName("streamChat：会话非进行中直接拒绝")
    void shouldRejectChatWhenSessionNotActive() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("1"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamChat(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("会话已结束，无法继续提问", ex.getMessage());
        verifyNoInteractions(messageMapper, quotaService, traceService);
    }

    @Test
    @DisplayName("streamChat：用户消息不存在直接拒绝")
    void shouldRejectChatWhenMessageAbsent() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamChat(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("消息不存在或不属于该会话", ex.getMessage());
        verifyNoInteractions(quotaService, traceService);
    }

    @Test
    @DisplayName("streamChat：用户消息不属于该会话直接拒绝")
    void shouldRejectChatWhenMessageNotOwned() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(userMessage(99L));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamChat(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("消息不存在或不属于该会话", ex.getMessage());
        verifyNoInteractions(quotaService, traceService);
    }

    @Test
    @DisplayName("streamChat：配额校验失败向上抛且不启动链路追踪")
    void shouldPropagateQuotaFailureWithoutStartingTrace() {
        AiSession session = session("0");
        AiMessage message = userMessage(SESSION_ID);
        AiAgent definition = mock(AiAgent.class);
        AiSessionResourceSet resources = AiSessionResourceSet.empty();
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session);
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        when(agentService.getEnabledAgent(7L)).thenReturn(definition);
        when(sessionResourceService.resolveForSession(session)).thenReturn(resources);
        doThrow(new ServiceException("配额已用尽")).when(quotaService).check(4L, definition);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(4L);

            ServiceException ex = assertThrows(ServiceException.class,
                () -> facade.streamChat(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));
            assertEquals("配额已用尽", ex.getMessage());
        }

        verify(quotaService).check(4L, definition);
        verifyNoInteractions(traceService);
    }

    // ---------------- streamChat：确定性驱动事件流终态 ----------------

    @Test
    @DisplayName("streamChat：空事件流同步收尾（完成分支）")
    void shouldCompleteChatStreamOnEmptyFlux() throws Exception {
        AiSession session = session("0");
        AiMessage message = userMessage(SESSION_ID);
        AiAgent definition = mock(AiAgent.class);
        AiSessionResourceSet resources = AiSessionResourceSet.empty();
        HarnessAgent agent = mock(HarnessAgent.class);
        AiMessage assistant = mock(AiMessage.class);
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session);
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        when(agentService.getEnabledAgent(7L)).thenReturn(definition);
        when(sessionResourceService.resolveForSession(session)).thenReturn(resources);
        when(skillService.resolveRuntimeSkillFilterKeys(any(), any(), any())).thenReturn(List.of());
        when(agentRegistry.get(definition, resources)).thenReturn(agent);
        when(attachmentService.listByMessageId(any())).thenReturn(List.of());
        when(agent.prepareRun(anyList(), any(RuntimeContext.class)))
            .thenReturn(AgentRun.create("test", () -> Flux.<AgentEvent>empty()));
        when(aiProperties.getChat()).thenReturn(new AiProperties.Chat());
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        when(assistant.getId()).thenReturn(200L);
        SseEmitter emitter = mock(SseEmitter.class);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(null);
            facade.streamChat(SESSION_ID, MESSAGE_ID, emitter);
        }

        verify(sessionService).persistAssistantTurn(any(), any(), any(), any(), any(), any(), any());
        verify(emitter).complete();
    }

    @Test
    @DisplayName("streamChat：事件流报错走失败收尾（onError 分支）")
    void shouldFailChatStreamOnFluxError() throws Exception {
        AiSession session = session("0");
        AiMessage message = userMessage(SESSION_ID);
        AiAgent definition = mock(AiAgent.class);
        AiSessionResourceSet resources = AiSessionResourceSet.empty();
        HarnessAgent agent = mock(HarnessAgent.class);
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session);
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        when(agentService.getEnabledAgent(7L)).thenReturn(definition);
        when(sessionResourceService.resolveForSession(session)).thenReturn(resources);
        when(skillService.resolveRuntimeSkillFilterKeys(any(), any(), any())).thenReturn(List.of());
        when(agentRegistry.get(definition, resources)).thenReturn(agent);
        when(attachmentService.listByMessageId(any())).thenReturn(List.of());
        when(agent.prepareRun(anyList(), any(RuntimeContext.class)))
            .thenReturn(AgentRun.create("test", () -> Flux.<AgentEvent>error(new RuntimeException("boom"))));
        SseEmitter emitter = mock(SseEmitter.class);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(null);
            facade.streamChat(SESSION_ID, MESSAGE_ID, emitter);
        }

        verify(emitter).completeWithError(any());
    }

    // ---------------- streamResume：前置校验 / 早退 ----------------

    @Test
    @DisplayName("streamResume：待确认消息不存在直接拒绝")
    void shouldRejectResumeWhenMessageAbsent() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamResume(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("待确认消息不存在或不属于该会话", ex.getMessage());
        verifyNoInteractions(pendingStore);
    }

    @Test
    @DisplayName("streamResume：待确认消息不属于该会话直接拒绝")
    void shouldRejectResumeWhenMessageNotOwned() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(userMessage(99L));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamResume(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("待确认消息不存在或不属于该会话", ex.getMessage());
        verifyNoInteractions(pendingStore);
    }

    @Test
    @DisplayName("streamResume：暂存已过期直接拒绝")
    void shouldRejectResumeWhenPendingExpired() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(userMessage(SESSION_ID));
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamResume(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("确认已过期或已被处理，请重新提问", ex.getMessage());
    }

    @Test
    @DisplayName("streamResume：尚无确认结果直接拒绝")
    void shouldRejectResumeWhenNoDecisions() {
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(userMessage(SESSION_ID));
        when(pendingStore.get(SESSION_ID, MESSAGE_ID))
            .thenReturn(Optional.of(pendingWithDecisions(List.of(), List.of(), null)));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamResume(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("尚未收到确认结果", ex.getMessage());
    }

    @Test
    @DisplayName("streamResume：专家恢复段占用失败直接拒绝")
    void shouldRejectResumeWhenClaimFails() {
        AiConfirmPendingStore.ExpertResume resume =
            new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first");
        List<AiConfirmPendingStore.PendingDecision> decisions =
            List.of(new AiConfirmPendingStore.PendingDecision("c1", true, false, null));
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session("0"));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(userMessage(SESSION_ID));
        when(pendingStore.get(SESSION_ID, MESSAGE_ID))
            .thenReturn(Optional.of(pendingWithDecisions(List.of(), decisions, resume)));
        when(pendingStore.claim(SESSION_ID, MESSAGE_ID)).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> facade.streamResume(SESSION_ID, MESSAGE_ID, mock(SseEmitter.class)));

        assertEquals("该确认正在处理中或已被处理，请稍候", ex.getMessage());
    }

    // ---------------- streamResume：确定性驱动事件流终态 ----------------

    @Test
    @DisplayName("streamResume：空事件流同步收尾（完成分支）")
    void shouldCompleteResumeStreamOnEmptyFlux() throws Exception {
        AiSession session = session("0");
        AiMessage message = userMessage(SESSION_ID);
        AiAgent definition = mock(AiAgent.class);
        AiSessionResourceSet resources = AiSessionResourceSet.empty();
        HarnessAgent agent = mock(HarnessAgent.class);
        AiMessage assistant = mock(AiMessage.class);
        List<AiConfirmPendingStore.PendingDecision> decisions =
            List.of(new AiConfirmPendingStore.PendingDecision("c1", true, false, null));
        AiConfirmPendingStore.PendingConfirm pending = new AiConfirmPendingStore.PendingConfirm(
            SESSION_ID, MESSAGE_ID, 7L, null, "reply-1", List.of(), decisions, "2026-01-01T00:00:00", null);
        when(sessionService.getOwnedSession(SESSION_ID)).thenReturn(session);
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        when(pendingStore.get(SESSION_ID, MESSAGE_ID)).thenReturn(Optional.of(pending));
        when(agentService.getEnabledAgent(7L)).thenReturn(definition);
        when(sessionResourceService.resolveForSession(session)).thenReturn(resources);
        when(skillService.resolveRuntimeSkillFilterKeys(any(), any(), any())).thenReturn(List.of());
        when(slotStore.merge(null, SESSION_ID)).thenReturn(AiSlotSet.empty());
        when(agentRegistry.get(definition, resources)).thenReturn(agent);
        when(agent.prepareRun(anyList(), any(RuntimeContext.class)))
            .thenReturn(AgentRun.create("test", () -> Flux.<AgentEvent>empty()));
        when(aiProperties.getChat()).thenReturn(new AiProperties.Chat());
        when(sessionService.appendAssistantTurn(any(), any(), any(), any(), any(), any())).thenReturn(assistant);
        when(assistant.getId()).thenReturn(200L);
        SseEmitter emitter = mock(SseEmitter.class);

        facade.streamResume(SESSION_ID, MESSAGE_ID, emitter);

        verify(sessionService).appendAssistantTurn(any(), any(), any(), any(), any(), any());
        verify(emitter).complete();
    }

    // ---------------- fixtures ----------------

    private static AiProperties.Chat chatWithTtl() {
        AiProperties.Chat chat = new AiProperties.Chat();
        chat.setPendingConfirmTtlMinutes(TTL_MINUTES);
        return chat;
    }

    private static AiSession session(String status) {
        AiSession session = new AiSession();
        session.setId(SESSION_ID);
        session.setStatus(status);
        session.setAgentId(7L);
        return session;
    }

    private static AiMessage userMessage(Long sessionId) {
        AiMessage message = new AiMessage();
        message.setId(MESSAGE_ID);
        message.setSessionId(sessionId);
        message.setContent("hi");
        return message;
    }

    private static AiConfirmPendingStore.PendingConfirm pendingWithDecisions(
        List<AiConfirmPendingStore.PendingToolCall> toolCalls,
        List<AiConfirmPendingStore.PendingDecision> decisions,
        AiConfirmPendingStore.ExpertResume expertResume) {
        return new AiConfirmPendingStore.PendingConfirm(SESSION_ID, MESSAGE_ID, 7L, 4L, "reply-1",
            toolCalls, decisions, "2026-01-01T00:00:00", expertResume);
    }

    private static AiMessageVo message(Long id) {
        AiMessageVo vo = new AiMessageVo();
        vo.setId(id);
        vo.setSessionId(SESSION_ID);
        return vo;
    }

    private static AiChatConfirmBo confirmBo(Long messageId, boolean confirmed, boolean remember) {
        AiChatConfirmBo bo = new AiChatConfirmBo();
        bo.setMessageId(messageId);
        bo.setConfirmed(confirmed);
        bo.setRemember(remember);
        return bo;
    }

    private static List<AiConfirmPendingStore.PendingToolCall> twoCalls() {
        return List.of(
            new AiConfirmPendingStore.PendingToolCall("c1", "create_notice", Map.of("a", 1), "{}"),
            new AiConfirmPendingStore.PendingToolCall("c2", "delete_user", Map.of("b", 2), "{}"));
    }

    private static AiConfirmPendingStore.PendingConfirm pending(
        List<AiConfirmPendingStore.PendingToolCall> toolCalls,
        AiConfirmPendingStore.ExpertResume expertResume) {
        return new AiConfirmPendingStore.PendingConfirm(SESSION_ID, MESSAGE_ID, 7L, 4L, "reply-1",
            toolCalls, List.of(), "2026-01-01T00:00:00", expertResume);
    }

    private static AiConfirmPendingStore.PendingConfirm withDecisions(
        AiConfirmPendingStore.PendingConfirm p, boolean confirmed, boolean remember, String reason) {
        List<AiConfirmPendingStore.PendingDecision> decisions = new ArrayList<>();
        for (AiConfirmPendingStore.PendingToolCall call : p.toolCalls()) {
            decisions.add(new AiConfirmPendingStore.PendingDecision(call.toolCallId(), confirmed, remember, reason));
        }
        return new AiConfirmPendingStore.PendingConfirm(p.sessionId(), p.messageId(), p.agentId(), p.userId(),
            p.replyId(), p.toolCalls(), decisions, p.createdAt(), p.expertResume());
    }

}
