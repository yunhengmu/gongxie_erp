package org.dromara.ai.core.agent;

import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.mapper.AiRouteExpertCallMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRouteRecorder} 单元测试：编排开始、收尾与专家调用明细落库。
 */
@DisplayName("编排链路落库")
class AiRouteRecorderTest {

    private final AiRouteRunMapper routeRunMapper = mock(AiRouteRunMapper.class);
    private final AiRouteExpertCallMapper expertCallMapper = mock(AiRouteExpertCallMapper.class);
    private final AiRouteRecorder recorder = new AiRouteRecorder(routeRunMapper, expertCallMapper);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    private static AiRouteContext context() {
        AiRouteContext ctx = new AiRouteContext(1L, 2L, 3L, 4L, 5L, List.of(), 6L, null);
        ctx.messageId = 77L;
        ctx.action = "route";
        ctx.decisionJson = "{\"reason\":\"x\"}";
        ctx.taskContext = "task";
        ctx.expertCallCount.set(2);
        return ctx;
    }

    @Test
    @DisplayName("开始编排写入运行中状态")
    void shouldStartRun() {
        when(routeRunMapper.insert(any(AiRouteRun.class))).thenReturn(1);
        recorder.startRun(1L, 2L, 3L);
        ArgumentCaptor<AiRouteRun> captor = ArgumentCaptor.forClass(AiRouteRun.class);
        verify(routeRunMapper).insert(captor.capture());
        AiRouteRun run = captor.getValue();
        assertEquals(1L, run.getSessionId());
        assertEquals(2L, run.getEntryAgentId());
        assertEquals(3L, run.getUserId());
        assertEquals(AiRouteRun.STATUS_RUNNING, run.getStatus());
        assertEquals(0, run.getExpertCallCount());
        assertNotNull(run.getCreateTime());
    }

    @Test
    @DisplayName("收尾编排更新运行记录")
    void shouldFinishRun() {
        recorder.finishRun(9L, context(), AiRouteRun.STATUS_SUCCESS, "原因", 123L);
        verify(routeRunMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("编排主键为空时跳过收尾")
    void shouldSkipFinishWhenRunIdNull() {
        recorder.finishRun(null, context(), AiRouteRun.STATUS_SUCCESS, null, 1L);
        verify(routeRunMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("超长降级原因被截断且不抛出")
    void shouldTruncateLongReason() {
        String reason = "x".repeat(800);
        assertDoesNotThrow(() -> recorder.finishRun(9L, context(), AiRouteRun.STATUS_DEGRADED, reason, 5L));
        verify(routeRunMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("收尾落库异常被吞掉")
    void shouldSwallowFinishFailure() {
        doThrow(new RuntimeException("db down")).when(routeRunMapper).update(isNull(), any());
        assertDoesNotThrow(() -> recorder.finishRun(9L, context(), AiRouteRun.STATUS_ABORTED, null, 1L));
    }

    @Test
    @DisplayName("记录专家调用明细并补全创建时间")
    void shouldRecordExpertCall() {
        AiRouteExpertCall call = new AiRouteExpertCall();
        call.setRouteRunId(1L);
        call.setExpertAgentId(2L);
        when(expertCallMapper.insert(any(AiRouteExpertCall.class))).thenReturn(1);
        recorder.recordExpertCall(call);
        assertNotNull(call.getCreateTime());
        verify(expertCallMapper).insert(call);
    }

    @Test
    @DisplayName("专家明细落库异常被吞掉")
    void shouldSwallowExpertCallFailure() {
        AiRouteExpertCall call = new AiRouteExpertCall();
        doThrow(new RuntimeException("db down")).when(expertCallMapper).insert(any(AiRouteExpertCall.class));
        assertDoesNotThrow(() -> recorder.recordExpertCall(call));
    }
}