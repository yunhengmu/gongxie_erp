package org.dromara.ai.core.agent;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AiStreamEventBridge} 单元测试：各类 AgentScope 事件的分发与状态累积。
 */
@DisplayName("流式事件桥接器")
class AiStreamEventBridgeTest {

    private final IAiTraceService traceService = mock(IAiTraceService.class);
    /** 安全守卫默认不生效（anyActive=false），走快速路径，保持既有断言 */
    private final AiSafetyGuard safetyGuard = mock(AiSafetyGuard.class);
    private final IAiSafetyEventService safetyEventService = mock(IAiSafetyEventService.class);
    private final AiStreamEventBridge bridge =
        new AiStreamEventBridge(traceService, new AiProperties(), safetyGuard, safetyEventService);
    private final SseEmitter emitter = mock(SseEmitter.class);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void stubSafety() {
        // 工具结果守卫默认「原样放行」，既有断言不受影响
        lenient().when(safetyGuard.guard(any(), any()))
            .thenAnswer(invocation -> SafetyDecision.pass(invocation.getArgument(0)));
    }

    private static AiStreamState state(Long traceId) {
        AiStreamState state = new AiStreamState();
        state.traceId = traceId;
        return state;
    }

    @Test
    @DisplayName("处理需人工确认事件暂存工具调用")
    void shouldHandleRequireConfirm() {
        AiStreamState state = state(100L);
        List<ToolUseBlock> calls = List.of(ToolUseBlock.builder()
            .id("c1").name("create_notice").input(Map.of("a", 1)).content("{}").build());
        bridge.handle(emitter, state, new RequireUserConfirmEvent("reply-1", calls));
        assertTrue(state.pendingConfirm);
        assertEquals("reply-1", state.pendingReplyId);
        assertEquals(1, state.pendingToolCalls.size());
        assertEquals("c1", state.pendingToolCalls.get(0).toolCallId());
    }

    @Test
    @DisplayName("确认事件工具列表为空时暂存空清单")
    void shouldHandleRequireConfirmWithoutCalls() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new RequireUserConfirmEvent("reply-1", null));
        assertTrue(state.pendingToolCalls.isEmpty());
    }

    @Test
    @DisplayName("正文增量累积并下发")
    void shouldHandleTextDelta() throws Exception {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new TextBlockDeltaEvent("reply-1", "block-1", "你好"));
        assertEquals("你好", state.answer.toString());
        assertEquals("reply-1", state.replyId);
        verify(emitter, times(1)).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("空白正文增量被忽略")
    void shouldIgnoreBlankTextDelta() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new TextBlockDeltaEvent("reply-1", "block-1", " "));
        assertEquals("", state.answer.toString());
        assertNull(state.replyId);
    }

    @Test
    @DisplayName("工具调用开始累积并记录链路节点")
    void shouldHandleToolCallStart() {
        AiStreamState state = state(100L);
        ToolCallStartEvent start = new ToolCallStartEvent("reply-1", "c1", "search");
        start.withSource("main/sub");
        bridge.handle(emitter, state, start);

        AiToolCallAcc acc = state.tools.get("c1");
        assertEquals("search", acc.toolName);
        assertEquals("main/sub", acc.source);
        verify(traceService).appendTransition(100L, "tool_call", "search@main/sub");
    }

    @Test
    @DisplayName("专家直答链路不下发工具事件但仍累积")
    void shouldAccumulateWithoutForwarding() throws Exception {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new ToolCallStartEvent("reply-1", "c1", "search"), false);
        bridge.handle(emitter, state, new ToolCallDeltaEvent("reply-1", "c1", "search", "{}"), false);
        assertEquals("search", state.tools.get("c1").toolName);
        assertEquals("{}", state.tools.get("c1").input.toString());
        verify(emitter, never()).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("工具入参增量累积")
    void shouldHandleToolCallDelta() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new ToolCallDeltaEvent("reply-1", "c1", "search", "{\"a\":"));
        bridge.handle(emitter, state, new ToolCallDeltaEvent("reply-1", "c1", "search", "1}"));
        AiToolCallAcc acc = state.tools.get("c1");
        assertEquals("search", acc.toolName);
        assertEquals("{\"a\":1}", acc.input.toString());
    }

    @Test
    @DisplayName("模型调用结束累加用量")
    void shouldHandleModelCallEnd() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new ModelCallEndEvent("reply-1", new ChatUsage(10, 20, 0.1)));
        assertEquals(10, state.promptTokens);
        assertEquals(20, state.completionTokens);
    }

    @Test
    @DisplayName("工具结果增量仅累积")
    void shouldHandleToolResultDelta() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new ToolResultTextDeltaEvent("reply-1", "c1", "search", "out"));
        assertEquals("out", state.tools.get("c1").output.toString());
    }

    @Test
    @DisplayName("工具结果结束落状态，失败记链路异常节点")
    void shouldHandleToolResultEnd() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new ToolCallStartEvent("reply-1", "c1", "search"));
        bridge.handle(emitter, state, new ToolResultEndEvent("reply-1", "c1", "search", ToolResultState.ERROR));
        AiToolCallAcc acc = state.tools.get("c1");
        assertEquals(ToolResultState.ERROR, acc.state);
        verify(traceService).appendTransition(100L, "tool_error", "search → ERROR");
    }

    @Test
    @DisplayName("成功结果不记链路异常节点")
    void shouldNotRecordErrorOnSuccess() {
        AiStreamState state = state(100L);
        bridge.handle(emitter, state, new ToolResultEndEvent("reply-1", "c1", "search", ToolResultState.SUCCESS));
        verify(traceService, never()).appendTransition(eq(100L), eq("tool_error"), eq("search → SUCCESS"));
    }

    @Test
    @DisplayName("忽略未知事件")
    void shouldIgnoreUnknownEvent() {
        AiStreamState state = state(100L);
        assertDoesNotThrow(() -> bridge.handle(emitter, state, null));
    }

    @Test
    @DisplayName("收尾冲刷尾部缓冲：补发未下发的尾部，保证前端所见 == 落库内容")
    void shouldFlushSafetyTailToEmitter() throws Exception {
        AiStreamState state = state(100L);
        state.answer.append("头部");
        state.safetyPending.append("尾部");
        bridge.flushSafety(emitter, state);
        assertEquals("头部尾部", state.answer.toString());
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("无待冲刷尾部时不下发")
    void shouldNotEmitWhenNoPendingTail() throws Exception {
        AiStreamState state = state(100L);
        state.answer.append("正文");
        bridge.flushSafety(emitter, state);
        assertEquals("正文", state.answer.toString());
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}