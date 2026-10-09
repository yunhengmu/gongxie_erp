package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiExpertResumeDriver} 的纯单元测试。
 *
 * <p>覆盖占用失败、恢复段上下文（resumed / routeRunId / 链路复用）、暂停 / 成功 / 失败 / 取消四类收尾。
 * 续跑用 mock 直接给定 {@code ResumeOutcome}，异步部分用 {@code timeout()} 有界等待，不引入 sleep。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("专家续跑驱动")
class AiExpertResumeDriverTest {

    private static final Long SESSION_ID = 1L;
    private static final Long MESSAGE_ID = 2L;
    private static final Long USER_ID = 4L;
    private static final Long DEPT_ID = 6L;
    private static final Long AGENT_ID = 7L;
    private static final Long MODEL_ID = 8L;
    private static final Long TRACE_ID = 100L;

    @Mock
    private IAiSessionService sessionService;
    @Mock
    private IAiAgentService agentService;
    @Mock
    private AgentRegistry agentRegistry;
    @Mock
    private AiConfirmPendingStore pendingStore;
    @Mock
    private IAiTraceService traceService;
    @Mock
    private IAiSessionResourceService sessionResourceService;
    @Mock
    private AiExpertDirectory expertDirectory;
    @Mock
    private AiSlotStore slotStore;
    @Mock
    private AiPermissionModeStore permissionModeStore;
    @Mock
    private AiRouteOrchestrator routeOrchestrator;
    @Mock
    private AiRunRegistry runRegistry;
    @Mock
    private AiTurnFinisher turnFinisher;
    @Mock
    private AiIntegrationDriver integrationDriver;
    @Mock
    private AiRouteSupport routeSupport;
    @Mock
    private AiTrustApplier trustApplier;
    @Mock
    private AiSession session;
    @Mock
    private AiAgent definition;
    @Mock
    private HarnessAgent agent;
    @Mock
    private SseEmitter emitter;

    private final AiSessionResourceSet resources = AiSessionResourceSet.empty();

    private AiExpertResumeDriver driver;

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        driver = new AiExpertResumeDriver(sessionService, agentService, agentRegistry, pendingStore, traceService,
            sessionResourceService, expertDirectory, slotStore, permissionModeStore, routeOrchestrator,
            runRegistry, turnFinisher, integrationDriver, routeSupport, trustApplier);
        when(session.getId()).thenReturn(SESSION_ID);
    }

    // ---------------- claim ----------------

    @Test
    @DisplayName("占用失败：抛业务异常且不登记执行")
    void shouldRejectWhenClaimFails() {
        when(pendingStore.claim(SESSION_ID, MESSAGE_ID)).thenReturn(false);
        AiConfirmPendingStore.PendingConfirm pending = pending(resume());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> driver.resumeRoute(session, MESSAGE_ID, pending, emitter));

        assertEquals("该确认正在处理中或已被处理，请稍候", ex.getMessage());
        verify(runRegistry, never()).register(any(), any());
    }

    // ---------------- 链路复用 ----------------

    @Test
    @DisplayName("复用既有链路：不再新建链路并追加恢复节点")
    void shouldReuseExistingTrace() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            when(pendingStore.claim(SESSION_ID, MESSAGE_ID)).thenReturn(true);
            when(traceService.findIdByMessage(SESSION_ID, MESSAGE_ID)).thenReturn(TRACE_ID);
            when(routeOrchestrator.resumeExpert(any(), any(), any())).thenAnswer(inv -> {
                AiRouteContext r = inv.getArgument(0);
                r.handoff = true;
                return new AiRouteOrchestrator.ResumeOutcome(false, "report");
            });

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            verify(traceService, never()).start(any(), any(), any(), any(), any());
            verify(traceService).appendTransition(eq(TRACE_ID), eq("resume"), eq("人工确认后恢复专家执行"));
            assertEquals(TRACE_ID, route.traceId);
        }
    }

    @Test
    @DisplayName("无既有链路：新建链路")
    void shouldStartTraceWhenAbsent() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            when(pendingStore.claim(SESSION_ID, MESSAGE_ID)).thenReturn(true);
            when(traceService.findIdByMessage(SESSION_ID, MESSAGE_ID)).thenReturn(null);
            when(traceService.start(any(), any(), any(), any(), any())).thenReturn(TRACE_ID);
            when(routeOrchestrator.resumeExpert(any(), any(), any())).thenAnswer(inv -> {
                AiRouteContext r = inv.getArgument(0);
                r.handoff = true;
                return new AiRouteOrchestrator.ResumeOutcome(false, "report");
            });

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            verify(traceService).start(eq(SESSION_ID), eq(AGENT_ID), eq(USER_ID), eq(AiTrace.SOURCE_CHAT), any());
            assertEquals(TRACE_ID, route.traceId);
        }
    }

    @Test
    @DisplayName("恢复段上下文：resumed 置位且编排主键取自暂存")
    void shouldMarkResumedAndReuseRouteRunId() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            stubTraceReuse();
            when(routeOrchestrator.resumeExpert(any(), any(), any())).thenAnswer(inv -> {
                AiRouteContext r = inv.getArgument(0);
                r.handoff = true;
                return new AiRouteOrchestrator.ResumeOutcome(false, "report");
            });

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            assertTrue(route.resumed);
            assertEquals(9L, route.routeRunId.longValue());
        }
    }

    // ---------------- 收尾分支 ----------------

    @Test
    @DisplayName("再次暂停：落待确认、中止运行并释放占用且不清暂存")
    void shouldPauseAgain() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            stubTraceReuse();
            when(routeOrchestrator.resumeExpert(any(), any(), any()))
                .thenReturn(new AiRouteOrchestrator.ResumeOutcome(true, null));

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            verify(turnFinisher, timeout(2000)).finishPendingResume(eq(emitter), eq(SESSION_ID), any(), anyLong(),
                eq(MESSAGE_ID), eq(route));
            verify(turnFinisher, timeout(2000)).finishRoute(eq(route), eq(MESSAGE_ID), eq(AiRouteRun.STATUS_PENDING),
                eq("等待人工确认"), anyLong());
            verify(pendingStore).release(SESSION_ID, MESSAGE_ID);
            verify(pendingStore, never()).remove(any(), any());
        }
    }

    @Test
    @DisplayName("续跑成功：收尾落库、中止终态为 success 并清理暂存")
    void shouldFinishSuccessWhenNotDegraded() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            stubTraceReuse();
            when(routeOrchestrator.resumeExpert(any(), any(), any())).thenAnswer(inv -> {
                AiRouteContext r = inv.getArgument(0);
                r.handoff = true;
                return new AiRouteOrchestrator.ResumeOutcome(false, "report");
            });

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            verify(turnFinisher, timeout(2000)).finish(eq(emitter), eq(SESSION_ID), any(), anyLong(),
                eq(MESSAGE_ID), eq(route));
            verify(turnFinisher, timeout(2000)).finishRoute(eq(route), isNull(), eq(AiRouteRun.STATUS_SUCCESS),
                isNull(), anyLong());
            verify(pendingStore).remove(SESSION_ID, MESSAGE_ID);
            verify(pendingStore).release(SESSION_ID, MESSAGE_ID);
        }
    }

    @Test
    @DisplayName("续跑降级：交由整合轮且不清暂存")
    void shouldDriveIntegrationWhenDegraded() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            stubTraceReuse();
            when(routeOrchestrator.resumeExpert(any(), any(), any()))
                .thenReturn(new AiRouteOrchestrator.ResumeOutcome(false, "report"));

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            verify(integrationDriver, timeout(2000)).driveIntegration(eq(agent), any(), eq(session), eq(MESSAGE_ID),
                eq(route), any(), eq("report"), anyString(), anyLong(), eq(emitter));
            verify(pendingStore, never()).remove(any(), any());
        }
    }

    @Test
    @DisplayName("续跑报错：释放占用、发失败信封并中止编排")
    void shouldAbortWhenResumeErrors() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            stubTraceReuse();
            when(routeOrchestrator.resumeExpert(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);

            AiRouteContext route = captureRoute(resume());
            verify(turnFinisher, timeout(2000)).failStream(eq(emitter), anyString(), any());
            verify(turnFinisher, timeout(2000)).finishRoute(eq(route), isNull(), eq(AiRouteRun.STATUS_ABORTED),
                anyString(), anyLong());
            verify(pendingStore).release(SESSION_ID, MESSAGE_ID);
        }
    }

    @Test
    @DisplayName("取消续跑：中止编排并释放占用")
    void shouldAbortOnCancel() throws Exception {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getDeptId).thenReturn(DEPT_ID);
            stubHappyPath();
            stubTraceReuse();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            when(routeOrchestrator.resumeExpert(any(), any(), any())).thenAnswer(inv -> {
                started.countDown();
                unblock.await(5, TimeUnit.SECONDS);
                return new AiRouteOrchestrator.ResumeOutcome(false, "report");
            });

            driver.resumeRoute(session, MESSAGE_ID, pending(resume()), emitter);
            assertTrue(started.await(2, TimeUnit.SECONDS));

            ArgumentCaptor<AiRunRegistry.Cancelable> handle = ArgumentCaptor.forClass(AiRunRegistry.Cancelable.class);
            verify(runRegistry).register(eq(SESSION_ID), handle.capture());
            assertTrue(handle.getValue().cancel());
            unblock.countDown();

            verify(turnFinisher).finishRoute(any(), isNull(), eq(AiRouteRun.STATUS_ABORTED),
                eq("用户停止生成"), anyLong());
            verify(traceService).appendTransition(any(), eq("cancelled"), eq("用户停止专家续跑"));
            verify(pendingStore).release(SESSION_ID, MESSAGE_ID);
        }
    }

    // ---------------- fixtures ----------------

    private static AiConfirmPendingStore.ExpertResume resume() {
        return new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first");
    }

    private static AiConfirmPendingStore.PendingConfirm pending(AiConfirmPendingStore.ExpertResume resume) {
        return new AiConfirmPendingStore.PendingConfirm(SESSION_ID, MESSAGE_ID, AGENT_ID, USER_ID, "reply-1",
            List.of(), List.of(), "2026-01-01T00:00:00", resume);
    }

    /**
     * 打桩恢复段的公共装配依赖（不含链路：既有链路复用 / 新建链路各自的用例单独打桩）。
     */
    private void stubHappyPath() {
        when(session.getAgentId()).thenReturn(AGENT_ID);
        when(agentService.getEnabledAgent(AGENT_ID)).thenReturn(definition);
        when(definition.getId()).thenReturn(AGENT_ID);
        when(definition.getModelId()).thenReturn(MODEL_ID);
        when(sessionResourceService.resolveForSession(session)).thenReturn(resources);
        when(agentRegistry.get(definition, resources)).thenReturn(agent);
        when(routeSupport.runtimeSkillFilter(AGENT_ID, resources, USER_ID)).thenReturn(SkillFilter.none());
        when(slotStore.merge(USER_ID, SESSION_ID)).thenReturn(AiSlotSet.empty());
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DONT_ASK);
        when(expertDirectory.candidates(USER_ID, DEPT_ID)).thenReturn(List.of());
    }

    private void stubTraceReuse() {
        when(pendingStore.claim(SESSION_ID, MESSAGE_ID)).thenReturn(true);
        when(traceService.findIdByMessage(SESSION_ID, MESSAGE_ID)).thenReturn(TRACE_ID);
    }

    /**
     * 从 {@code resumeExpert} 的调用参数中取回恢复段编排上下文（并确保异步回调已完成）。
     */
    private AiRouteContext captureRoute(AiConfirmPendingStore.ExpertResume resume) throws Exception {
        ArgumentCaptor<AiRouteContext> cap = ArgumentCaptor.forClass(AiRouteContext.class);
        verify(routeOrchestrator, timeout(2000)).resumeExpert(cap.capture(), eq(resume), any());
        return cap.getValue();
    }
}
