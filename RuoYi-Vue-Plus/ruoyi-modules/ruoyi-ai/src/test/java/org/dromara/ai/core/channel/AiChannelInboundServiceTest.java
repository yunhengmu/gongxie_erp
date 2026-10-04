package org.dromara.ai.core.channel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import org.dromara.ai.core.agent.AiAgentTaskRunner;
import org.dromara.ai.core.agent.AiChatFacade;
import org.dromara.ai.domain.AiChannelThread;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.dto.AiTaskExecution;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiChannelService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelInboundService} 单元测试：入站归一化处理链路与各拒绝/异常分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("渠道入站消息处理")
class AiChannelInboundServiceTest {

    @Mock
    private IAiChannelService channelService;
    @Mock
    private IAiSessionService sessionService;
    @Mock
    private AiChatFacade chatFacade;
    @Mock
    private UserService userService;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiAgentTaskRunner taskRunner;
    @Mock
    private AiChannelRuntime runtime;

    private AiChannelInboundService service;

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        service = new AiChannelInboundService(channelService, sessionService, chatFacade,
            userService, messageMapper, taskRunner);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(taskRunner).submit(any(Runnable.class));
        when(runtime.getChannelId()).thenReturn(1L);
        when(runtime.getChannelCode()).thenReturn("ch-1");
        when(runtime.getAgentId()).thenReturn(9L);
        when(runtime.send(any(), anyList())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("无文本内容时回复仅支持文本")
    void shouldReplyWhenTextBlank() {
        service.submit(runtime, inboundWithMessages(List.of()));

        assertTrue(lastReply().contains("未识别到文本内容"));
    }

    @Test
    @DisplayName("外部身份未映射时回复未绑定提示")
    void shouldReplyWhenUnmapped() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(null);

        service.submit(runtime, inbound("你好"));

        assertTrue(lastReply().contains("尚未绑定平台用户"));
        verify(chatFacade, never()).executeUnattended(any(), any(), any());
    }

    @Test
    @DisplayName("映射的平台用户不存在时回复用户缺失提示")
    void shouldReplyWhenUserMissing() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(null);

        service.submit(runtime, inbound("你好"));

        assertTrue(lastReply().contains("平台用户不存在"));
    }

    @Test
    @DisplayName("会话创建失败（智能体不可用）时回复停用提示")
    void shouldReplyWhenSessionCreateFails() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        when(channelService.findThread(1L, "direct:u1")).thenReturn(null);
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenThrow(new RuntimeException("agent disabled"));

        service.submit(runtime, inbound("你好"));

        assertTrue(lastReply().contains("智能体不存在或已停用"));
    }

    @Test
    @DisplayName("成功路径：新建会话、执行并回复助手内容")
    void shouldHandleSuccessNewSession() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        when(channelService.findThread(1L, "direct:u1")).thenReturn(null);
        AiSession created = session(100L, "0");
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenReturn(created);
        AiMessage userMessage = new AiMessage();
        when(sessionService.appendUserMessageUnattended(100L, "你好")).thenReturn(userMessage);
        when(chatFacade.executeUnattended(created, userMessage, 5L))
            .thenReturn(execution(100L, 200L, true, null));
        AiMessage assistant = new AiMessage();
        assistant.setContent("助手回答");
        when(messageMapper.selectById(200L)).thenReturn(assistant);

        service.submit(runtime, inbound("你好"));

        assertEquals("助手回答", lastReply());
        verify(channelService).bindThread(1L, "direct:u1", 100L);
        verify(channelService).touchThread(1L, "direct:u1");
    }

    @Test
    @DisplayName("已有进行中会话时续用，不新建")
    void shouldReuseActiveSession() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        AiChannelThread thread = new AiChannelThread();
        thread.setSessionId(100L);
        when(channelService.findThread(1L, "direct:u1")).thenReturn(thread);
        when(sessionService.getSession(100L)).thenReturn(session(100L, "0"));
        AiMessage userMessage = new AiMessage();
        when(sessionService.appendUserMessageUnattended(100L, "你好")).thenReturn(userMessage);
        when(chatFacade.executeUnattended(any(), any(), any()))
            .thenReturn(execution(100L, null, true, null));

        service.submit(runtime, inbound("你好"));

        verify(sessionService, never()).createUnattendedSession(any(), anyString(), any(), any(), anyString());
        assertEquals("（本轮无文本回答）", lastReply());
    }

    @Test
    @DisplayName("已绑定会话已结束或读取失败时新建")
    void shouldCreateWhenSessionEnded() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        AiChannelThread thread = new AiChannelThread();
        thread.setSessionId(100L);
        when(channelService.findThread(1L, "direct:u1")).thenReturn(thread);
        when(sessionService.getSession(100L)).thenReturn(session(100L, "1"));
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenReturn(session(101L, "0"));
        when(sessionService.appendUserMessageUnattended(101L, "你好")).thenReturn(new AiMessage());
        when(chatFacade.executeUnattended(any(), any(), any())).thenReturn(execution(101L, null, false, "err"));

        service.submit(runtime, inbound("你好"));

        verify(sessionService).createUnattendedSession(any(), anyString(), any(), any(), anyString());
        assertTrue(lastReply().contains("执行失败"));
    }

    @Test
    @DisplayName("读取会话抛异常时静默转为新建")
    void shouldCreateWhenLoadSessionThrows() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        AiChannelThread thread = new AiChannelThread();
        thread.setSessionId(100L);
        when(channelService.findThread(1L, "direct:u1")).thenReturn(thread);
        when(sessionService.getSession(100L)).thenThrow(new RuntimeException("gone"));
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenReturn(session(102L, "0"));
        when(sessionService.appendUserMessageUnattended(any(), anyString())).thenReturn(new AiMessage());
        when(chatFacade.executeUnattended(any(), any(), any())).thenReturn(null);

        service.submit(runtime, inbound("你好"));

        verify(sessionService).createUnattendedSession(any(), anyString(), any(), any(), anyString());
        assertTrue(lastReply().contains("未返回结果"));
    }

    @Test
    @DisplayName("执行返回成功但助手消息为空时回复无文本回答")
    void shouldReplyWhenAssistantBlank() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        when(channelService.findThread(1L, "direct:u1")).thenReturn(null);
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenReturn(session(100L, "0"));
        when(sessionService.appendUserMessageUnattended(any(), anyString())).thenReturn(new AiMessage());
        when(chatFacade.executeUnattended(any(), any(), any())).thenReturn(execution(100L, 200L, true, null));
        AiMessage assistant = new AiMessage();
        assistant.setContent("  ");
        when(messageMapper.selectById(200L)).thenReturn(assistant);

        service.submit(runtime, inbound("你好"));

        assertTrue(lastReply().contains("本轮无文本回答"));
    }

    @Test
    @DisplayName("执行链路抛异常时回复执行异常信息")
    void shouldReplyWhenExecutionThrows() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(5L);
        when(userService.selectById(5L)).thenReturn(user());
        when(channelService.findThread(1L, "direct:u1")).thenReturn(null);
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenReturn(session(100L, "0"));
        when(sessionService.appendUserMessageUnattended(any(), anyString()))
            .thenThrow(new RuntimeException("db-down"));

        service.submit(runtime, inbound("你好"));

        assertTrue(lastReply().contains("执行异常"));
    }

    @Test
    @DisplayName("出站回复失败只告警不抛异常")
    void shouldTolerateReplyFailure() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(null);
        when(runtime.send(any(), anyList())).thenThrow(new RuntimeException("send-down"));

        assertDoesNotThrow(() -> service.submit(runtime, inbound("你好")));
    }

    @Test
    @DisplayName("线程型 peer 组装出站地址并携带 threadId")
    void shouldBuildThreadedOutboundAddress() {
        when(channelService.resolvePlatformUserId(1L, "u1")).thenReturn(null);
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        Peer peer = Peer.thread("t-1");
        InboundMessage inbound = InboundMessage.builder("ch-1", peer, List.of(msg)).senderId("u1").build();

        service.submit(runtime, inbound);

        ArgumentCaptor<OutboundAddress> captor = ArgumentCaptor.forClass(OutboundAddress.class);
        verify(runtime).send(captor.capture(), anyList());
        assertEquals("ch-1:THREAD:t-1", captor.getValue().to());
        assertEquals("t-1", captor.getValue().threadId());
    }

    @SuppressWarnings("unchecked")
    private String lastReply() {
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(runtime, times(1)).send(any(OutboundAddress.class), captor.capture());
        return captor.getValue().get(0).getTextContent();
    }

    private InboundMessage inbound(String text) {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return InboundMessage.builder("ch-1", Peer.direct("u1"), List.of(msg)).senderId("u1").build();
    }

    private InboundMessage inboundWithMessages(List<Msg> messages) {
        return InboundMessage.builder("ch-1", Peer.direct("u1"), messages).senderId("u1").build();
    }

    private UserDTO user() {
        UserDTO user = new UserDTO();
        user.setUserId(5L);
        user.setDeptId(7L);
        return user;
    }

    private AiSession session(Long id, String status) {
        AiSession session = new AiSession();
        session.setId(id);
        session.setStatus(status);
        return session;
    }

    private AiTaskExecution execution(Long sessionId, Long messageId, boolean success, String errorMsg) {
        return new AiTaskExecution(sessionId, messageId, success, errorMsg, 1L, 0, 1, 1, 2);
    }

}