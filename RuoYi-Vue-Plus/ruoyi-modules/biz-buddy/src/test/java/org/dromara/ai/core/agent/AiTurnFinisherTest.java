package org.dromara.ai.core.agent;

import io.agentscope.core.message.ToolResultState;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiTurnFinisher} 的纯单元测试（不碰运行时 / Redis / 数据库）。
 *
 * <p>覆盖正常收尾、取消收尾、待确认暂停收尾、决策留痕过滤、done 信封与失败信封、编排运行收尾
 * 与失败下发等分支；SSE 载荷通过对 {@code SseEmitter.send} 的 {@code SseEventBuilder} 参数解析 JSON 断言。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("一轮对话的收尾落库与留痕")
class AiTurnFinisherTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 4L;
    private static final Long AGENT_ID = 7L;
    private static final Long MODEL_ID = 8L;
    private static final Long TRACE_ID = 100L;
    private static final Long MESSAGE_ID = 2L;

    @Mock
    private IAiSessionService sessionService;
    @Mock
    private IAiPermissionDecisionService decisionService;
    @Mock
    private IAiUsageStatService usageStatService;
    @Mock
    private IAiTraceService traceService;
    @Mock
    private IAiMemoryService memoryService;
    @Mock
    private AiRouteRecorder routeRecorder;
    @Mock
    private AiConfirmPendingStore pendingStore;

    private final AiProperties aiProperties = new AiProperties();

    private AiTurnFinisher finisher;

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        finisher = new AiTurnFinisher(sessionService, decisionService, usageStatService,
            traceService, memoryService, aiProperties, routeRecorder, pendingStore);
    }

    // ---------------- finish ----------------

    @Test
    @DisplayName("finish：非恢复段落库新助手消息")
    void shouldPersistNewAssistantTurn() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        AiMessage result = finisher.finish(emitter, SESSION_ID, state, 10L, null, null);

        assertEquals(200L, result.getId());
        verify(sessionService).persistAssistantTurn(eq(SESSION_ID), any(), any(), any(), any(), any(), any());
        verify(sessionService, never()).appendAssistantTurn(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("finish：恢复段追加更新同一条消息且请求数计 0")
    void shouldAppendAssistantTurnForResume() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.appendAssistantTurn(any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        AiMessage result = finisher.finish(emitter, SESSION_ID, state, 10L, MESSAGE_ID, null);

        assertEquals(200L, result.getId());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(sessionService).appendAssistantTurn(eq(MESSAGE_ID), any(), status.capture(), any(), any(), any());
        assertEquals(AiMessage.STATUS_NORMAL, status.getValue());
        verify(sessionService, never()).persistAssistantTurn(any(), any(), any(), any(), any(), any(), any());
        verify(usageStatService).accumulate(eq(USER_ID), eq(AGENT_ID), eq(MODEL_ID), any(), eq(0), eq(0));
    }

    @Test
    @DisplayName("finish：专家直答接管态正文取 handoffAnswer")
    void shouldPreferHandoffAnswerWhenPresent() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.answer.append("入口正文");
        AiRouteContext route = route();
        route.handoff = true;
        route.handoffAnswer.append("专家正文");
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, null, route);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sessionService).persistAssistantTurn(eq(SESSION_ID), content.capture(), any(), any(), any(), any(), any());
        assertEquals("专家正文", content.getValue());
    }

    @Test
    @DisplayName("finish：接管态但专家缓冲为空时回落入口正文")
    void shouldFallBackToStateAnswerWhenHandoffBufferEmpty() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.answer.append("入口正文");
        AiRouteContext route = route();
        route.handoff = true;
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, null, route);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sessionService).persistAssistantTurn(eq(SESSION_ID), content.capture(), any(), any(), any(), any(), any());
        assertEquals("入口正文", content.getValue());
    }

    @Test
    @DisplayName("finish：恢复段跳过入口轮决策补写")
    void shouldSkipEntryAllowDecisionsForResume() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.appendAssistantTurn(any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.tools.put("c1", acc("create_notice", ToolResultState.SUCCESS));
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, MESSAGE_ID, null);

        verify(decisionService, never()).recordAllow(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("finish：专家调用决策留痕跳过未执行/被拒的工具")
    void shouldRecordExpertAllowOnlyForExecutedTools() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        AiRouteContext route = route();
        route.expertToolLogs.add(log(null, "a", "SUCCESS"));
        route.expertToolLogs.add(log(AGENT_ID, "  ", "SUCCESS"));
        route.expertToolLogs.add(log(AGENT_ID, "b", null));
        route.expertToolLogs.add(log(AGENT_ID, "c", ToolResultState.DENIED.name()));
        route.expertToolLogs.add(log(AGENT_ID, "ok", "SUCCESS"));
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, null, route);

        verify(decisionService, times(1)).recordAllow(any(), any(), any(), any(), any(), eq("ok"), any(), any());
    }

    @Test
    @DisplayName("finish：入参决策留痕记录已执行工具（非恢复段）")
    void shouldRecordEntryAllowDecisionsForExecutedTools() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.tools.put("c1", acc("create_notice", ToolResultState.SUCCESS));
        state.tools.put("c2", acc("pending_tool", null));
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, null, null);

        verify(decisionService, times(1)).recordAllow(eq(SESSION_ID), eq(200L), eq(AGENT_ID), eq(USER_ID),
            eq("create_notice"), eq("create_notice"), any(), any());
    }

    @Test
    @DisplayName("finish：tokens 为空时 done 信封不含 totalTokens 且请求数计 1")
    void shouldOmitTotalTokensAndCountFirstRequest() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, null, null);

        String done = jsonOfType(emitter, "done");
        assertFalse(done.contains("totalTokens"), done);
        verify(usageStatService).accumulate(eq(USER_ID), eq(AGENT_ID), eq(MODEL_ID), any(), eq(0), eq(1));
    }

    @Test
    @DisplayName("finish：tokens 非空时 done 信封含 totalTokens")
    void shouldIncludeTotalTokensWhenPresent() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.promptTokens = 3;
        state.completionTokens = 5;
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finish(emitter, SESSION_ID, state, 10L, null, null);

        String done = jsonOfType(emitter, "done");
        assertTrue(done.contains("\"totalTokens\":8"), done);
    }

    @Test
    @DisplayName("finish：落库失败发错误信封并关闭流，返回 null")
    void shouldSendErrorAndReturnNullWhenPersistFails() throws Exception {
        when(sessionService.persistAssistantTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("db down"));
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        AiMessage result = finisher.finish(emitter, SESSION_ID, state, 10L, null, null);

        assertNull(result);
        String error = jsonOfType(emitter, "error");
        assertTrue(error.contains("回答已生成但保存失败：db down"), error);
        verify(emitter).complete();
    }

    // ---------------- finishCancelled ----------------

    @Test
    @DisplayName("finishCancelled：非恢复段落库已中止消息并下发 aborted")
    void shouldPersistAbortedForNewMessage() throws Exception {
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finishCancelled(emitter, SESSION_ID, state, 10L, null, null);

        verify(sessionService).persistAssistantAborted(eq(SESSION_ID), any(), eq(10L));
        verify(sessionService, never()).appendAssistantTurn(any(), any(), any(), any(), any(), any());
        verify(traceService).accumulate(eq(TRACE_ID), eq(AiTrace.STATUS_ABORTED), eq(10L), any(), any(), any(), any(), any());
        assertTrue(jsonOfType(emitter, "aborted") != null);
        verify(emitter).complete();
    }

    @Test
    @DisplayName("finishCancelled：恢复段追加更新同一条消息")
    void shouldAppendAbortedForResumeMessage() throws Exception {
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finishCancelled(emitter, SESSION_ID, state, 10L, MESSAGE_ID, null);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(sessionService).appendAssistantTurn(eq(MESSAGE_ID), any(), status.capture(), any(), any(), any());
        assertEquals(AiMessage.STATUS_ABORTED, status.getValue());
        verify(traceService).accumulate(eq(TRACE_ID), eq(AiTrace.STATUS_ABORTED), eq(10L), any(), any(), any(), any(), any());
        assertTrue(jsonOfType(emitter, "aborted") != null);
    }

    // ---------------- finishPending / finishPendingResume ----------------

    @Test
    @DisplayName("finishPending：专家暂停落平台文案并回填消息主键")
    void shouldUsePlatformTextWhenExpertPaused() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistPendingTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.answer.append("残句");
        AiRouteContext route = route();
        route.pendingResume = new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first");
        SseEmitter emitter = mock(SseEmitter.class);

        Long result = finisher.finishPending(emitter, SESSION_ID, state, 10L, route);

        assertEquals(200L, result);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sessionService).persistPendingTurn(eq(SESSION_ID), content.capture(), any(), any(), any(), any(), any());
        assertEquals("专家准备执行的操作需要你确认后才会继续，请在下方确认卡片中处置。\n\n", content.getValue());
        verify(usageStatService).accumulate(eq(USER_ID), eq(AGENT_ID), eq(MODEL_ID), any(), eq(0), eq(1));
    }

    @Test
    @DisplayName("finishPending：入口暂停落累积正文")
    void shouldUseStateAnswerWhenEntryPaused() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistPendingTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.answer.append("入口暂停正文");
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finishPending(emitter, SESSION_ID, state, 10L, null);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sessionService).persistPendingTurn(eq(SESSION_ID), content.capture(), any(), any(), any(), any(), any());
        assertEquals("入口暂停正文", content.getValue());
    }

    @Test
    @DisplayName("finishPending：只落已产生结果的工具明细")
    void shouldOnlyPersistFinishedToolLogs() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.persistPendingTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.tools.put("c1", acc("done_tool", ToolResultState.SUCCESS));
        state.tools.put("c2", acc("pending_tool", null));
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finishPending(emitter, SESSION_ID, state, 10L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiToolCallLog>> logs = ArgumentCaptor.forClass(List.class);
        verify(sessionService).persistPendingTurn(eq(SESSION_ID), any(), any(), any(), logs.capture(), any(), any());
        assertEquals(1, logs.getValue().size());
        assertEquals("done_tool", logs.getValue().get(0).getToolName());
    }

    @Test
    @DisplayName("finishPending：落库失败发等待确认失败信封")
    void shouldSendPendingSaveErrorWhenPersistFails() throws Exception {
        when(sessionService.persistPendingTurn(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));
        AiStreamState state = state();
        SseEmitter emitter = mock(SseEmitter.class);

        Long result = finisher.finishPending(emitter, SESSION_ID, state, 10L, null);

        assertNull(result);
        String error = jsonOfType(emitter, "error");
        assertTrue(error.contains("等待确认的消息保存失败：boom"), error);
        verify(emitter).complete();
    }

    @Test
    @DisplayName("finishPendingResume：专家再次暂停正文为空串且状态为待确认")
    void shouldClearContentAndMarkPendingOnResumePause() throws Exception {
        AiMessage assistant = mockAssistant(200L);
        when(sessionService.appendAssistantTurn(any(), any(), any(), any(), any(), any()))
            .thenReturn(assistant);
        AiStreamState state = state();
        state.answer.append("不应落库");
        AiRouteContext route = route();
        route.pendingResume = new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first");
        SseEmitter emitter = mock(SseEmitter.class);

        finisher.finishPendingResume(emitter, SESSION_ID, state, 10L, MESSAGE_ID, route);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(sessionService).appendAssistantTurn(eq(MESSAGE_ID), content.capture(), status.capture(), any(), any(), any());
        assertEquals("", content.getValue());
        assertEquals(AiMessage.STATUS_PENDING_CONFIRM, status.getValue());
        verify(usageStatService).accumulate(eq(USER_ID), eq(AGENT_ID), eq(MODEL_ID), any(), eq(0), eq(0));
    }

    // ---------------- finishRoute ----------------

    @Test
    @DisplayName("finishRoute：route 为空直接返回且不触达落库")
    void shouldNoopWhenRouteNull() {
        finisher.finishRoute(null, MESSAGE_ID, AiRouteRun.STATUS_SUCCESS, null, 10L);

        verifyNoInteractions(routeRecorder);
    }

    @Test
    @DisplayName("finishRoute：非恢复段空白 action 兜底为 self")
    void shouldDefaultSelfActionWhenNotResumed() {
        AiRouteContext route = route();

        finisher.finishRoute(route, MESSAGE_ID, AiRouteRun.STATUS_SUCCESS, null, 10L);

        assertEquals(AiRouteRun.ACTION_SELF, route.action);
        verify(routeRecorder).finishRun(eq(route.routeRunId), eq(route), eq(AiRouteRun.STATUS_SUCCESS), any(), eq(10L));
    }

    @Test
    @DisplayName("finishRoute：恢复段不把空 action 兜底为 self")
    void shouldKeepBlankActionWhenResumed() {
        AiRouteContext route = route();
        route.resumed = true;

        finisher.finishRoute(route, MESSAGE_ID, AiRouteRun.STATUS_SUCCESS, null, 10L);

        assertNull(route.action);
        verify(routeRecorder).finishRun(eq(route.routeRunId), eq(route), eq(AiRouteRun.STATUS_SUCCESS), any(), eq(10L));
    }

    // ---------------- failStream ----------------

    @Test
    @DisplayName("failStream：emitter 为空不抛异常")
    void shouldNotThrowWhenEmitterNull() {
        assertDoesNotThrow(() -> finisher.failStream(null, "boom", new RuntimeException("x")));
    }

    @Test
    @DisplayName("failStream：下发错误信封并以异常关闭流")
    void shouldSendErrorAndCompleteWithError() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        RuntimeException error = new RuntimeException("x");

        finisher.failStream(emitter, "boom", error);

        String json = jsonOfType(emitter, "error");
        assertTrue(json.contains("\"message\":\"boom\""), json);
        verify(emitter).completeWithError(error);
    }

    // ---------------- fixtures ----------------

    private static AiStreamState state() {
        AiStreamState state = new AiStreamState();
        state.sessionId = SESSION_ID;
        state.userId = USER_ID;
        state.agentId = AGENT_ID;
        state.modelId = MODEL_ID;
        state.traceId = TRACE_ID;
        return state;
    }

    private static AiRouteContext route() {
        return new AiRouteContext(9L, SESSION_ID, USER_ID, null, AGENT_ID, List.of(), TRACE_ID, null);
    }

    private static AiMessage mockAssistant(Long id) {
        AiMessage assistant = mock(AiMessage.class);
        when(assistant.getId()).thenReturn(id);
        return assistant;
    }

    private static AiToolCallAcc acc(String toolName, ToolResultState toolState) {
        AiToolCallAcc acc = new AiToolCallAcc();
        acc.toolName = toolName;
        acc.state = toolState;
        acc.input.append("{}");
        return acc;
    }

    private static AiToolCallLog log(Long agentId, String toolName, String toolState) {
        AiToolCallLog log = new AiToolCallLog();
        log.setAgentId(agentId);
        log.setToolName(toolName);
        log.setState(toolState);
        log.setSessionId(SESSION_ID);
        log.setToolInput("{}");
        return log;
    }

    /**
     * 解析指定类型的事件信封 JSON（对 {@code emitter.send} 的 {@code SseEventBuilder} 参数解析）。
     *
     * @param emitter SSE 发射器（mock）
     * @param type    事件类型
     * @return 该类型的事件 JSON；未下发时返回 null
     */
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
