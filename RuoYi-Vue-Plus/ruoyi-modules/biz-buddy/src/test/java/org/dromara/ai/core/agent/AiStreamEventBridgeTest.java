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
import org.dromara.ai.core.AiRetrievalSourceRegistry;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyAction;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.vo.AiSourceVo;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.ai.util.AiPiiMasker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;
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
    private final AiRetrievalSourceRegistry retrievalSourceRegistry = new AiRetrievalSourceRegistry();
    private final AiStreamEventBridge bridge =
        new AiStreamEventBridge(traceService, new AiProperties(), safetyGuard, safetyEventService,
            retrievalSourceRegistry);
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
    @DisplayName("正文跨片切断手机号 / 邮箱时仍被脱敏（切点对齐安全边界）")
    void shouldMaskPiiSplitAcrossDeltas() {
        // 复刻实测分片：手机号被切成「158 / 88888 / 888」、邮箱被切成「m / ybizb / uddy@163 / .com」
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.holdBackChars()).thenReturn(32);
        // 让守卫真正做 PII 脱敏（等价生产实现里 PII 那一段）
        when(safetyGuard.guard(any(), any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            AiPiiMasker.Outcome outcome = AiPiiMasker.mask(text);
            return outcome.count() > 0
                ? new SafetyDecision(SafetyAction.MASK, outcome.text(), true, outcome.types())
                : SafetyDecision.pass(text);
        });

        AiStreamState state = state(100L);
        for (String piece : List.of("- 手机号码：158", "88888", "888\n- 邮箱：m", "ybizb",
            "uddy@163", ".com\n")) {
            bridge.handle(emitter, state, new TextBlockDeltaEvent("reply-1", "block-1", piece));
        }
        bridge.flushSafety(emitter, state);

        // state.answer 即「已下发内容」的累积（onTextDelta 对同一字符串既 append 又 emit）
        String shown = state.answer.toString();
        assertFalse(shown.contains("15888888888"), "手机号不得以明文下发：" + shown);
        assertFalse(shown.contains("mybizbuddy@163.com"), "邮箱不得以明文下发：" + shown);
        assertTrue(shown.contains("158****8888"), "手机号应已掩码：" + shown);
        assertTrue(shown.contains("m***@163.com"), "邮箱应已掩码：" + shown);
    }

    @Test
    @DisplayName("流式正文命中 PII 时当场留痕，且每轮只记一条")
    void shouldRecordOutputHitOncePerRound() {
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.holdBackChars()).thenReturn(32);
        when(safetyGuard.guard(any(), any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            AiPiiMasker.Outcome outcome = AiPiiMasker.mask(text);
            return outcome.count() > 0
                ? new SafetyDecision(SafetyAction.MASK, outcome.text(), true, outcome.types())
                : SafetyDecision.pass(text);
        });

        AiStreamState state = state(100L);
        state.sessionId = 1L;
        state.userId = 2L;
        for (String piece : List.of("- 手机号码：158", "88888", "888\n- 邮箱：m", "ybizb",
            "uddy@163", ".com\n")) {
            bridge.handle(emitter, state, new TextBlockDeltaEvent("reply-1", "block-1", piece));
        }
        bridge.flushSafety(emitter, state);

        // 多个分片先后命中，但只应留痕一条（否则审计会被逐片刷屏）
        verify(safetyEventService, times(1)).record(eq(SafetyScene.OUTPUT), any(SafetyDecision.class),
            any(), eq(1L), any(), any(), eq(2L), any());
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

    @Test
    @DisplayName("工具结果命中来源时下发 references 事件并累积")
    void shouldEmitReferencesOnToolResult() throws Exception {
        AiStreamState state = state(100L);
        state.replyId = "reply-1";
        retrievalSourceRegistry.put("c1", List.of(source(11L, 0, 0.8)));

        bridge.handle(emitter, state, new ToolResultEndEvent("reply-1", "c1", "search", ToolResultState.SUCCESS));

        assertEquals(1, state.sources.size());
        String json = jsonOfType(emitter, "references");
        assertTrue(json != null, "应下发 references 事件");
        assertTrue(json.contains("\"docId\":11"), json);
        assertTrue(json.contains("\"toolCallId\":\"c1\""), json);
    }

    @Test
    @DisplayName("同 docId + chunkIndex 的来源去重并保留最高相似度")
    void shouldDedupeSourcesKeepingMaxScore() {
        AiStreamState state = state(100L);
        retrievalSourceRegistry.put("c1", List.of(source(11L, 0, 0.5)));
        bridge.handle(emitter, state, new ToolResultEndEvent("reply-1", "c1", "search", ToolResultState.SUCCESS));
        retrievalSourceRegistry.put("c2", List.of(source(11L, 0, 0.9)));
        bridge.handle(emitter, state, new ToolResultEndEvent("reply-1", "c2", "search", ToolResultState.SUCCESS));

        assertEquals(1, state.sources.size());
        assertEquals(Double.valueOf(0.9), state.sources.get(0).getScore());
    }

    @Test
    @DisplayName("专家直答链路只累积来源不下发 references")
    void shouldAccumulateSourcesWithoutForwarding() throws Exception {
        AiStreamState state = state(100L);
        retrievalSourceRegistry.put("c1", List.of(source(11L, 0, 0.8)));

        bridge.handle(emitter, state,
            new ToolResultEndEvent("reply-1", "c1", "search", ToolResultState.SUCCESS), false);

        assertEquals(1, state.sources.size());
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private static AiSourceVo source(Long docId, Integer chunkIndex, double score) {
        AiSourceVo vo = new AiSourceVo();
        vo.setDocId(docId);
        vo.setChunkIndex(chunkIndex);
        vo.setDocName("文档" + docId);
        vo.setScore(score);
        return vo;
    }

    /**
     * 解析指定类型的事件信封 JSON（对 {@code emitter.send} 的 {@code SseEventBuilder} 参数解析）。
     */
    private static String jsonOfType(SseEmitter emitter, String type) throws Exception {
        org.mockito.ArgumentCaptor<SseEmitter.SseEventBuilder> cap =
            org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(cap.capture());
        for (SseEmitter.SseEventBuilder builder : cap.getAllValues()) {
            for (org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType data : builder.build()) {
                String json = String.valueOf(data.getData());
                if (json.contains("\"type\":\"" + type + "\"")) {
                    return json;
                }
            }
        }
        return null;
    }
}