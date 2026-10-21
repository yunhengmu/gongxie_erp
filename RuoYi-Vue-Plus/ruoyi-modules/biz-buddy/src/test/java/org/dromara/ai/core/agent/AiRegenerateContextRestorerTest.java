package org.dromara.ai.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.mapper.AiMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRegenerateContextRestorer} 单元测试：尾部截断、兜底重放与失败拒绝。
 */
@DisplayName("重新生成上下文回退")
class AiRegenerateContextRestorerTest {

    private final HarnessAgent agent = mock(HarnessAgent.class);
    private final ReActAgent delegate = mock(ReActAgent.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final AiRegenerateContextRestorer restorer = new AiRegenerateContextRestorer(messageMapper);

    @Test
    @DisplayName("尾部形态符合预期时原位删除本轮用户消息与旧回答")
    void shouldTruncateTail() {
        AgentState state = AgentState.builder()
            .context(new ArrayList<>(List.of(
                msg(MsgRole.USER, "问题一"),
                msg(MsgRole.ASSISTANT, "回答一"),
                msg(MsgRole.USER, "问题二"),
                msg(MsgRole.ASSISTANT, "回答二"))))
            .build();
        when(agent.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState("9", "1")).thenReturn(state);
        when(messageMapper.selectById(3L)).thenReturn(userMessage(3L, "问题二"));

        assertTrue(restorer.restore(agent, 9L, 1L, 3L));
        assertEquals(2, state.getContext().size());
        assertEquals("回答一", state.getContext().get(1).getTextContent());
        verify(delegate).saveAgentState("9", "1");
    }

    @Test
    @DisplayName("尾部文本不一致时清空并按历史轮次重放")
    void shouldReplayHistoryWhenTailMismatched() {
        AgentState oldState = AgentState.builder()
            .context(new ArrayList<>(List.of(
                msg(MsgRole.USER, "问题一"),
                msg(MsgRole.ASSISTANT, "回答一"),
                msg(MsgRole.USER, "被掩码后的提问"),
                msg(MsgRole.ASSISTANT, "回答二"))))
            .build();
        AgentState freshState = AgentState.builder().context(new ArrayList<>()).build();
        when(agent.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState("9", "1")).thenReturn(oldState, freshState);
        when(messageMapper.selectById(3L)).thenReturn(userMessage(3L, "原始提问"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
            message(1L, "user", "问题一"),
            message(2L, "assistant", "回答一")));

        assertTrue(restorer.restore(agent, 9L, 1L, 3L));

        verify(delegate).clearContext("9", "1");
        assertEquals(2, freshState.getContext().size());
        assertEquals("问题一", freshState.getContext().get(0).getTextContent());
        assertEquals("回答一", freshState.getContext().get(1).getTextContent());
        verify(delegate).saveAgentState("9", "1");
    }

    @Test
    @DisplayName("尾部无助手消息且重放异常时返回失败")
    void shouldFailWhenBothStrategiesFail() {
        AgentState state = AgentState.builder()
            .context(new ArrayList<>(List.of(msg(MsgRole.USER, "问题一"))))
            .build();
        when(agent.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState("9", "1")).thenReturn(state);
        doThrow(new IllegalStateException("redis down")).when(delegate).clearContext(anyString(), anyString());

        assertFalse(restorer.restore(agent, 9L, 1L, 3L));
    }

    @Test
    @DisplayName("重放只保留用户与助手正文，跳过空内容")
    void shouldReplayOnlyUserAndAssistantText() {
        AgentState oldState = AgentState.builder()
            .context(new ArrayList<>(List.of(msg(MsgRole.USER, "问题一"))))
            .build();
        AgentState freshState = AgentState.builder().context(new ArrayList<>()).build();
        when(agent.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState("9", "1")).thenReturn(oldState, freshState);
        when(messageMapper.selectList(any())).thenReturn(List.of(
            message(1L, "user", "问题一"),
            message(2L, "assistant", "  "),
            message(3L, "system", "系统提示"),
            message(4L, "assistant", "回答一")));

        assertTrue(restorer.restore(agent, 9L, 1L, 5L));

        assertEquals(2, freshState.getContext().size());
        assertEquals(MsgRole.USER, freshState.getContext().get(0).getRole());
        assertEquals(MsgRole.ASSISTANT, freshState.getContext().get(1).getRole());
    }

    private static Msg msg(MsgRole role, String text) {
        return Msg.builder().role(role).textContent(text).build();
    }

    private static AiMessage userMessage(Long id, String content) {
        AiMessage message = new AiMessage();
        message.setId(id);
        message.setRole(AiMessage.ROLE_USER);
        message.setContent(content);
        return message;
    }

    private static AiMessage message(Long id, String role, String content) {
        AiMessage message = new AiMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

}
