package org.dromara.ai.core.agent;

import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiStreamState} 单元测试：用量累积、用量汇总与工具明细构造。
 */
@DisplayName("对话流式累积状态")
class AiStreamStateTest {

    @Test
    @DisplayName("模型未返回用量时汇总为空")
    void shouldReturnNullUsageWhenNothingAccumulated() {
        AiStreamState state = new AiStreamState();
        assertNull(state.toTokenUsage());
        state.accumulateUsage(null);
        assertNull(state.toTokenUsage());
    }

    @Test
    @DisplayName("累加多次模型调用用量")
    void shouldAccumulateUsage() {
        AiStreamState state = new AiStreamState();
        state.accumulateUsage(new ChatUsage(100, 50, 20, 1.0));
        state.accumulateUsage(new ChatUsage(30, 10, 5, 0.5));
        assertEquals(130, state.promptTokens);
        assertEquals(60, state.completionTokens);
        assertEquals(25, state.cachedTokens);
        ChatTokenUsage usage = state.toTokenUsage();
        assertEquals(130, usage.promptTokens());
        assertEquals(60, usage.completionTokens());
        assertEquals(190, usage.totalTokens());
    }

    @Test
    @DisplayName("由累积状态构造工具明细")
    void shouldBuildToolLogs() {
        AiStreamState state = new AiStreamState();
        AiToolCallAcc acc = new AiToolCallAcc();
        acc.toolName = "search_user";
        acc.input.append("{\"id\":1}");
        acc.output.append("0123456789");
        acc.state = ToolResultState.SUCCESS;
        acc.durationMs = 12L;
        state.tools.put("call-1", acc);

        AiToolCallAcc unnamed = new AiToolCallAcc();
        unnamed.input.append("{}");
        state.tools.put("call-2", unnamed);

        List<AiToolCallLog> logs = state.toToolLogs(4, 99L);
        assertEquals(2, logs.size());
        AiToolCallLog first = logs.stream().filter(l -> "call-1".equals(l.getToolCallId())).findFirst().orElseThrow();
        assertEquals("search_user", first.getToolName());
        assertEquals("{\"id\":1}", first.getToolInput());
        assertEquals("0123...（已截断）", first.getToolOutput());
        assertEquals("SUCCESS", first.getState());
        assertEquals(12L, first.getDurationMs());
        assertEquals(99L, first.getAgentId());

        AiToolCallLog second = logs.stream().filter(l -> "call-2".equals(l.getToolCallId())).findFirst().orElseThrow();
        assertEquals("unknown", second.getToolName());
        assertNull(second.getState());
    }

    @Test
    @DisplayName("空工具映射返回空明细")
    void shouldReturnEmptyLogsWhenNoTools() {
        assertTrue(new AiStreamState().toToolLogs(10, null).isEmpty());
    }
}