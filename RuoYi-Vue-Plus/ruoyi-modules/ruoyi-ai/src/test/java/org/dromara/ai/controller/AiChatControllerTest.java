package org.dromara.ai.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiChatFacade;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.bo.AiChatConfirmBo;
import org.dromara.ai.domain.bo.AiChatPinExpertBo;
import org.dromara.ai.domain.bo.AiChatSendBo;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.bo.AiSessionResourceBatchBo;
import org.dromara.ai.domain.vo.AiChatPendingVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiSessionResourceVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.ai.service.IAiRouteService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatController} 单元测试：会话生命周期、消息收发与 SSE 建立。
 */
@DisplayName("AI 对话工作台 Controller")
class AiChatControllerTest {

    private final IAiSessionService sessionService = mock(IAiSessionService.class);

    private final IAiSessionResourceService resourceService = mock(IAiSessionResourceService.class);

    private final IAiRouteService routeService = mock(IAiRouteService.class);

    private final AiChatFacade facade = mock(AiChatFacade.class);

    private final AiChatController controller =
        new AiChatController(sessionService, resourceService, routeService, facade, new AiProperties());

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("创建会话返回会话详情")
    void shouldCreateSession() {
        AiSessionVo vo = new AiSessionVo();
        when(sessionService.createSession(1L, 2L, "标题", 3L)).thenReturn(vo);
        assertSame(vo, controller.createSession(1L, 2L, "标题", 3L).getData());
    }

    @Test
    @DisplayName("查询会话详情")
    void shouldGetSession() {
        AiSessionVo vo = new AiSessionVo();
        when(sessionService.getOwnedSessionVo(5L)).thenReturn(vo);
        assertSame(vo, controller.getSession(5L).getData());
    }

    @Test
    @DisplayName("指定与取消指定专家")
    void shouldPinExpert() {
        AiChatPinExpertBo bo = new AiChatPinExpertBo();
        bo.setExpertAgentId(7L);
        when(sessionService.setPinnedExpert(5L, 7L)).thenReturn(true);
        assertTrue(R.isSuccess(controller.pinExpert(5L, bo)));

        when(sessionService.setPinnedExpert(5L, null)).thenReturn(false);
        assertFalse(R.isSuccess(controller.pinExpert(5L, null)));
        verify(sessionService).setPinnedExpert(5L, null);
    }

    @Test
    @DisplayName("查询会话级资源集")
    void shouldListResources() {
        List<AiSessionResourceVo> list = List.of(new AiSessionResourceVo());
        when(resourceService.queryResources(5L)).thenReturn(list);
        assertSame(list, controller.listResources(5L).getData());
        verify(sessionService).getOwnedSession(5L);
    }

    @Test
    @DisplayName("批量增删资源转发到资源服务")
    void shouldAddAndRemoveResources() {
        AiSessionResourceBatchBo bo = new AiSessionResourceBatchBo();
        when(resourceService.addResources(5L, bo)).thenReturn(true);
        when(resourceService.removeResources(5L, bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.addResources(5L, bo)));
        assertTrue(R.isSuccess(controller.removeResources(5L, bo)));
    }

    @Test
    @DisplayName("查询我的会话列表")
    void shouldListSession() {
        AiSessionBo bo = new AiSessionBo();
        PageQuery query = new PageQuery();
        PageResult<AiSessionVo> page = PageResult.build();
        when(sessionService.queryMySessions(bo, query)).thenReturn(page);
        assertSame(page, controller.listSession(bo, query).getData());
    }

    @Test
    @DisplayName("查询会话消息与工具调用明细")
    void shouldListMessageAndToolCalls() {
        List<AiMessageVo> messages = List.of(new AiMessageVo());
        List<AiToolCallLogVo> calls = List.of(new AiToolCallLogVo());
        when(sessionService.queryMessages(5L)).thenReturn(messages);
        when(sessionService.queryOwnedToolCalls(5L)).thenReturn(calls);
        assertSame(messages, controller.listMessage(5L).getData());
        assertSame(calls, controller.listToolCalls(5L).getData());
    }

    @Test
    @DisplayName("查询会话编排轨迹")
    void shouldListRouteRuns() {
        List<AiRouteRunDetailVo> runs = List.of(new AiRouteRunDetailVo());
        when(routeService.querySessionRuns(5L)).thenReturn(runs);
        assertSame(runs, controller.listRouteRuns(5L).getData());
    }

    @Test
    @DisplayName("发送消息落库并回填字段")
    void shouldSendMessage() {
        AiChatSendBo bo = new AiChatSendBo();
        bo.setContent("你好");
        AiMessage message = new AiMessage();
        message.setId(100L);
        message.setSessionId(5L);
        message.setRole(AiMessage.ROLE_USER);
        message.setContent("你好");
        message.setCreateTime(java.time.LocalDateTime.now());
        when(sessionService.appendUserMessage(5L, "你好")).thenReturn(message);

        AiMessageVo vo = controller.sendMessage(5L, bo).getData();
        assertEquals(100L, vo.getId());
        assertEquals(5L, vo.getSessionId());
        assertEquals(AiMessage.ROLE_USER, vo.getRole());
        assertEquals("你好", vo.getContent());
        assertNotNull(vo.getCreateTime());
    }

    @Test
    @DisplayName("删除一问一答")
    void shouldDeleteMessage() {
        when(sessionService.deleteChatRound(5L, 100L)).thenReturn(true);
        assertTrue(R.isSuccess(controller.deleteMessage(5L, 100L)));
    }

    @Test
    @DisplayName("确认与查询未决确认")
    void shouldConfirmAndListPending() {
        AiChatConfirmBo bo = new AiChatConfirmBo();
        List<AiChatPendingVo> pending = List.of(new AiChatPendingVo());
        when(facade.listPending(5L)).thenReturn(pending);
        assertTrue(R.isSuccess(controller.confirm(5L, bo)));
        assertSame(pending, controller.listPending(5L).getData());
        verify(facade).confirm(5L, bo);
    }

    @Test
    @DisplayName("建立 SSE 流取回答")
    void shouldStream() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        SseEmitter emitter = controller.stream(5L, 100L, response);
        assertNotNull(emitter);
        verify(facade).streamChat(eq(5L), eq(100L), any());
    }

    @Test
    @DisplayName("SSE 流建立异常时以错误结束")
    void shouldStreamWithError() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        doThrow(new RuntimeException("boom")).when(facade).streamChat(eq(5L), eq(100L), any());
        SseEmitter emitter = controller.stream(5L, 100L, response);
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("恢复被 HITL 暂停的对话")
    void shouldResume() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        SseEmitter emitter = controller.resume(5L, 100L, response);
        assertNotNull(emitter);
        verify(facade).streamResume(eq(5L), eq(100L), any());
    }

    @Test
    @DisplayName("终止、重命名、置顶会话")
    void shouldCloseRenameTop() {
        when(sessionService.closeSession(5L)).thenReturn(true);
        when(sessionService.renameSession(5L, "新标题")).thenReturn(true);
        when(sessionService.toggleTop(5L, Boolean.TRUE)).thenReturn(false);
        assertTrue(R.isSuccess(controller.closeSession(5L)));
        assertTrue(R.isSuccess(controller.renameSession(5L, "新标题")));
        assertFalse(R.isSuccess(controller.topSession(5L, Boolean.TRUE)));
    }

}