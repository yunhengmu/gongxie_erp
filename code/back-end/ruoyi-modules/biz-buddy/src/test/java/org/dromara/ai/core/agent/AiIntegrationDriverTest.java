package org.dromara.ai.core.agent;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiIntegrationDriver} 的纯单元测试。
 *
 * <p>覆盖指定专家兜底（审计字段、answer_reset、四分支）与整合轮驱动（routeDone 时序、暂存对、
 * 取消分支、运行生命周期与 SSE 取消绑定）。{@code routeOrchestrator.executePinned} 的运行异步发生，
 * 用 {@code timeout()} 有界等待，不引入 sleep。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("整合轮与指定专家兜底驱动")
class AiIntegrationDriverTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 4L;
    private static final Long AGENT_ID = 7L;
    private static final Long TRACE_ID = 100L;
    private static final Long MESSAGE_ID = 2L;

    @Mock
    private IAiSessionService sessionService;
    @Mock
    private AiConfirmPendingStore pendingStore;
    @Mock
    private IAiTraceService traceService;
    @Mock
    private AiRouteOrchestrator routeOrchestrator;
    @Mock
    private AiStreamEventBridge streamBridge;
    @Mock
    private AiRunRegistry runRegistry;
    @Mock
    private AiTurnFinisher turnFinisher;
    @Mock
    private HarnessAgent agent;
    @Mock
    private AiSession session;
    @Mock
    private RuntimeContext ctx;
    @Mock
    private SseEmitter emitter;
    @Mock
    private AgentRun<AgentEvent> run;

    private AiIntegrationDriver driver;

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        driver = new AiIntegrationDriver(sessionService, pendingStore, traceService, routeOrchestrator,
            streamBridge, runRegistry, turnFinisher);
        when(session.getId()).thenReturn(SESSION_ID);
    }

    // ---------------- drivePinnedFallback ----------------

    @Test
    @DisplayName("drivePinnedFallback：先钉审计动作、下发 answer_reset 并清空正文")
    void shouldPrepareAuditAndResetAnswer() throws Exception {
        AiRouteContext route = route();
        route.handoff = true;
        route.pinnedExpertId = 77L;
        route.pinSource = "user";
        AiStreamState state = state();
        state.answer.append("模型自答");
        when(routeOrchestrator.executePinned(route)).thenReturn("report");

        driver.drivePinnedFallback(agent, ctx, session, route, state, 0L, emitter);

        assertEquals(AiRouteRun.ACTION_PINNED, route.action);
        assertNotNull(route.decisionJson);
        assertTrue(route.decisionJson.contains("\"action\":\"pinned\""), route.decisionJson);
        assertTrue(route.decisionJson.contains("\"pin_source\":\"user\""), route.decisionJson);
        assertTrue(route.decisionJson.contains("\"expert_agent_id\":77"), route.decisionJson);
        assertTrue(route.decisionJson.contains("\"fallback\":true"), route.decisionJson);
        assertTrue(route.decisionJson.contains("\"note\":"), route.decisionJson);
        assertEquals(0, state.answer.length());
        assertNotNull(jsonOfType(emitter, "answer_reset"));
        verify(turnFinisher, timeout(2000)).finish(eq(emitter), eq(SESSION_ID), eq(state), anyLong(), isNull(), eq(route));
    }

    @Test
    @DisplayName("drivePinnedFallback：命中需确认时落待确认且不清暂存")
    void shouldPauseWhenPendingResume() throws Exception {
        AiRouteContext route = route();
        route.handoff = true;
        AiStreamState state = state();
        when(routeOrchestrator.executePinned(route)).thenAnswer(inv -> {
            route.pendingResume = new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first");
            return "report";
        });

        driver.drivePinnedFallback(agent, ctx, session, route, state, 0L, emitter);

        verify(turnFinisher, timeout(2000)).finishPending(eq(emitter), eq(SESSION_ID), eq(state), anyLong(), eq(route));
        verify(turnFinisher, timeout(2000)).finishRoute(eq(route), any(), eq(AiRouteRun.STATUS_PENDING),
            eq("等待人工确认"), anyLong());
        verify(pendingStore, never()).remove(any(), any());
    }

    @Test
    @DisplayName("drivePinnedFallback：降级时交由整合轮且不触暂存")
    void shouldDriveIntegrationWhenDegraded() throws Exception {
        when(agent.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(run);
        when(run.stream()).thenReturn(Flux.<AgentEvent>empty());
        AiRouteContext route = route();
        route.handoff = true;
        AiStreamState state = state();
        when(routeOrchestrator.executePinned(route)).thenAnswer(inv -> {
            route.degraded = true;
            return "report";
        });

        driver.drivePinnedFallback(agent, ctx, session, route, state, 0L, emitter);

        verify(turnFinisher, timeout(2000)).finishRoute(eq(route), isNull(), eq(AiRouteRun.STATUS_DEGRADED),
            isNull(), anyLong());
        assertTrue(route.routeDone);
        verify(pendingStore, never()).remove(any(), any());
    }

    @Test
    @DisplayName("drivePinnedFallback：编排报错发失败信封并中止编排")
    void shouldAbortWhenOrchestrationErrors() throws Exception {
        AiRouteContext route = route();
        route.handoff = true;
        AiStreamState state = state();
        when(routeOrchestrator.executePinned(route)).thenThrow(new RuntimeException("boom"));

        driver.drivePinnedFallback(agent, ctx, session, route, state, 0L, emitter);

        verify(turnFinisher, timeout(2000)).failStream(eq(emitter), anyString(), any());
        verify(turnFinisher, timeout(2000)).finishRoute(eq(route), isNull(), eq(AiRouteRun.STATUS_ABORTED),
            anyString(), anyLong());
        verify(traceService).appendTransition(eq(TRACE_ID), eq("error"), anyString());
    }

    // ---------------- driveIntegration ----------------

    @Test
    @DisplayName("driveIntegration：构建运行前已置 routeDone")
    void shouldSetRouteDoneBeforePreparingRun() {
        AiRouteContext route = route();
        AiStreamState state = state();
        boolean[] observedAtRunBuild = new boolean[1];
        when(agent.prepareRun(anyList(), any(RuntimeContext.class))).thenAnswer(inv -> {
            observedAtRunBuild[0] = route.routeDone;
            return run;
        });
        when(run.stream()).thenReturn(Flux.<AgentEvent>empty());

        driver.driveIntegration(agent, ctx, session, null, route, state, "report", "intro", 0L, emitter);

        assertTrue(observedAtRunBuild[0]);
        assertTrue(route.routeDone);
    }

    @Test
    @DisplayName("driveIntegration：无暂存消息时不触达 pendingStore")
    void shouldNotTouchPendingStoreWhenMessageIdNull() throws Exception {
        AiRouteContext route = route();
        AiStreamState state = state();
        when(agent.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(run);
        when(run.stream()).thenReturn(Flux.<AgentEvent>empty());

        driver.driveIntegration(agent, ctx, session, null, route, state, "report", "intro", 0L, emitter);

        verify(turnFinisher, timeout(2000)).finish(eq(emitter), eq(SESSION_ID), eq(state), anyLong(), isNull(), eq(route));
        verifyNoInteractions(pendingStore);
    }

    @Test
    @DisplayName("driveIntegration：恢复段先移除暂存再释放占用")
    void shouldRemoveThenReleasePendingStore() throws Exception {
        AiRouteContext route = route();
        AiStreamState state = state();
        when(agent.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(run);
        when(run.stream()).thenReturn(Flux.<AgentEvent>empty());

        driver.driveIntegration(agent, ctx, session, MESSAGE_ID, route, state, "report", "intro", 0L, emitter);

        verify(turnFinisher, timeout(2000)).finishRoute(eq(route), isNull(), eq(AiRouteRun.STATUS_SUCCESS),
            isNull(), anyLong());
        InOrder inOrder = inOrder(pendingStore);
        inOrder.verify(pendingStore).remove(SESSION_ID, MESSAGE_ID);
        inOrder.verify(pendingStore).release(SESSION_ID, MESSAGE_ID);
    }

    @Test
    @DisplayName("driveIntegration：取消分支按已中止收尾并释放占用")
    void shouldFinishCancelledWhenRunCancelled() throws Exception {
        AiRouteContext route = route();
        AiStreamState state = state();
        when(agent.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(run);
        when(run.stream()).thenReturn(Flux.<AgentEvent>error(new RuntimeException("boom")));
        when(run.status()).thenReturn(AgentRun.Status.CANCELLED);

        driver.driveIntegration(agent, ctx, session, MESSAGE_ID, route, state, "report", "intro", 0L, emitter);

        verify(turnFinisher, timeout(2000)).finishCancelled(eq(emitter), eq(SESSION_ID), eq(state), anyLong(),
            eq(MESSAGE_ID), eq(route));
        verify(pendingStore).release(SESSION_ID, MESSAGE_ID);
        verify(pendingStore, never()).remove(any(), any());
    }

    @Test
    @DisplayName("driveIntegration：登记/注销成对且 SSE 三处均绑定取消")
    void shouldPairRegistryLifecycleAndBindCancel() {
        AiRouteContext route = route();
        AiStreamState state = state();
        when(agent.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(run);
        when(run.stream()).thenReturn(Flux.<AgentEvent>empty());

        driver.driveIntegration(agent, ctx, session, null, route, state, "report", "intro", 0L, emitter);

        ArgumentCaptor<AiRunRegistry.Cancelable> handle = ArgumentCaptor.forClass(AiRunRegistry.Cancelable.class);
        verify(runRegistry).register(eq(SESSION_ID), handle.capture());
        verify(runRegistry).unregister(eq(SESSION_ID), eq(handle.getValue()));

        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(runnable.capture());
        verify(emitter).onTimeout(runnable.capture());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> consumer = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(consumer.capture());
        runnable.getAllValues().forEach(Runnable::run);
        consumer.getValue().accept(new RuntimeException("x"));

        verify(run, times(3)).cancel();
    }

    // ---------------- fixtures ----------------

    private static AiStreamState state() {
        AiStreamState state = new AiStreamState();
        state.sessionId = SESSION_ID;
        state.userId = USER_ID;
        state.agentId = AGENT_ID;
        state.traceId = TRACE_ID;
        return state;
    }

    private static AiRouteContext route() {
        return new AiRouteContext(9L, SESSION_ID, USER_ID, null, AGENT_ID, List.of(), TRACE_ID, null);
    }

    private static String jsonOfType(SseEmitter emitter, String type) throws Exception {
        ArgumentCaptor<SseEmitter.SseEventBuilder> cap = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(cap.capture());
        for (SseEmitter.SseEventBuilder builder : cap.getAllValues()) {
            for (ResponseBodyEmitter.DataWithMediaType data : builder.build()) {
                String json = String.valueOf(data.getData());
                if (json.contains("\"type\":\"" + type + "\"")) {
                    return json;
                }
            }
        }
        return null;
    }
}
