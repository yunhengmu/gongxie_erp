package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.agentscope.core.state.AgentStateStore;
import org.dromara.ai.core.agent.AiExpertDirectory;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiRegenerateCheckVo;
import org.dromara.ai.domain.vo.AiSessionExportVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiScenarioPackMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.mapper.AiToolCallLogMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiSessionServiceImpl} 单元测试：会话创建、消息落库、一轮删除与归属校验。
 */
@DisplayName("AI 会话服务")
class AiSessionServiceImplTest {

    private final AiSessionMapper baseMapper = mock(AiSessionMapper.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final AiToolCallLogMapper toolCallLogMapper = mock(AiToolCallLogMapper.class);
    private final AiToolMapper toolMapper = mock(AiToolMapper.class);
    private final AiScenarioPackMapper scenarioPackMapper = mock(AiScenarioPackMapper.class);
    private final IAiAgentService agentService = mock(IAiAgentService.class);
    private final IAiScenarioPackService scenarioPackService = mock(IAiScenarioPackService.class);
    private final UserService userService = mock(UserService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final AgentStateStore aiAgentStateStore = mock(AgentStateStore.class);
    private final AiExpertDirectory expertDirectory = mock(AiExpertDirectory.class);
    private final IAiChatAttachmentService aiChatAttachmentService = mock(IAiChatAttachmentService.class);
    private final AiSessionServiceImpl service = new AiSessionServiceImpl(baseMapper, messageMapper, toolCallLogMapper,
        toolMapper, scenarioPackMapper, agentService, scenarioPackService, userService, deptService, aiAgentStateStore,
        expertDirectory, aiChatAttachmentService);

    private MockedStatic<LoginHelper> loginHelper;

    @BeforeAll
    static void initMybatis() {
        // LambdaQueryWrapper 需要实体 TableInfo（纯单测不起容器，手工注册一次）
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        loginHelper = mockStatic(LoginHelper.class);
        loginHelper.when(LoginHelper::getUserId).thenReturn(9L);
        loginHelper.when(LoginHelper::getDeptId).thenReturn(3L);
    }

    @AfterEach
    void tearDown() {
        loginHelper.close();
    }

    @Test
    @DisplayName("创建会话成功并固化智能体")
    void shouldCreateSession() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        agent.setAgentCode("entry_x");
        when(agentService.getEnabledAgent(1L)).thenReturn(agent);
        when(scenarioPackService.getEnabledPackOrNull(null)).thenReturn(null);
        when(baseMapper.insert(any(AiSession.class))).thenReturn(1);
        AiSessionVo vo = sessionVo();
        when(baseMapper.selectVoById(any())).thenReturn(vo);

        assertSame(vo, service.createSession(1L, null, "标题", null));
        verify(scenarioPackService).validateSelectable(null);
    }

    @Test
    @DisplayName("创建会话标题为空时使用默认标题")
    void shouldUseDefaultTitleWhenBlank() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        agent.setAgentCode("entry_x");
        when(agentService.getEnabledAgent(1L)).thenReturn(agent);
        when(scenarioPackService.getEnabledPackOrNull(null)).thenReturn(null);
        when(baseMapper.insert(any(AiSession.class))).thenReturn(1);
        when(baseMapper.selectVoById(any())).thenReturn(sessionVo());

        service.createSession(1L, null, "  ", null);

        ArgumentCaptor<AiSession> captor = ArgumentCaptor.forClass(AiSession.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals("与 小Z 的对话", captor.getValue().getSessionTitle());
    }

    @Test
    @DisplayName("创建会话固化场景包")
    void shouldCreateSessionWithPack() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        when(agentService.getEnabledAgent(1L)).thenReturn(agent);
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(2L);
        pack.setPackCode("fin");
        when(scenarioPackService.getEnabledPackOrNull(2L)).thenReturn(pack);
        when(baseMapper.insert(any(AiSession.class))).thenReturn(1);
        when(baseMapper.selectVoById(any())).thenReturn(sessionVo());

        service.createSession(1L, 2L, "标题", null);

        ArgumentCaptor<AiSession> captor = ArgumentCaptor.forClass(AiSession.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getScenarioPackId());
        assertEquals("fin", captor.getValue().getScenarioPackCode());
    }

    @Test
    @DisplayName("指定专家不在可见范围时拒绝创建会话")
    void shouldRejectInvisiblePinnedExpert() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        when(agentService.getEnabledAgent(1L)).thenReturn(agent);
        when(scenarioPackService.getEnabledPackOrNull(null)).thenReturn(null);
        when(expertDirectory.visibleExpert(9L, 3L, 7L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.createSession(1L, null, "标题", 7L));
    }

    @Test
    @DisplayName("创建会话落库失败抛出异常")
    void shouldThrowWhenInsertFails() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        when(agentService.getEnabledAgent(1L)).thenReturn(agent);
        when(scenarioPackService.getEnabledPackOrNull(null)).thenReturn(null);
        when(baseMapper.insert(any(AiSession.class))).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.createSession(1L, null, "标题", null));
    }

    @Test
    @DisplayName("指定或取消指定专家")
    void shouldSetPinnedExpert() {
        AiSession session = session(1L, 9L, "0");
        when(baseMapper.selectById(1L)).thenReturn(session);
        when(baseMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.setPinnedExpert(1L, null));
    }

    @Test
    @DisplayName("访问非本人会话抛出异常")
    void shouldRejectForeignSession() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 8L, "0"));
        assertThrows(ServiceException.class, () -> service.getOwnedSession(1L));
        assertThrows(ServiceException.class, () -> service.getOwnedSession(null));
    }

    @Test
    @DisplayName("查询本人会话视图回填展示名")
    void shouldGetOwnedSessionVo() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiSessionVo vo = sessionVo();
        when(baseMapper.selectVoById(1L)).thenReturn(vo);

        assertSame(vo, service.getOwnedSessionVo(1L));
    }

    @Test
    @DisplayName("追加用户消息并在默认标题时改写标题")
    void shouldAppendUserMessage() {
        AiSession session = session(1L, 9L, "0");
        session.setSessionTitle("  ");
        when(baseMapper.selectById(1L)).thenReturn(session);
        when(baseMapper.updateById(any(AiSession.class))).thenReturn(1);

        AiMessage message = service.appendUserMessage(1L, "帮我查订单");

        assertEquals(AiMessage.ROLE_USER, message.getRole());
        assertEquals("帮我查订单", message.getContent());
        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals(AiMessage.ROLE_USER, captor.getValue().getRole());
    }

    @Test
    @DisplayName("会话已结束时拒绝继续提问")
    void shouldRejectAppendWhenSessionClosed() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "1"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.appendUserMessage(1L, "hi"));
        assertEquals("会话已结束，无法继续提问", ex.getMessage());
    }

    @Test
    @DisplayName("无人值守创建会话使用默认来源")
    void shouldCreateUnattendedSession() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        when(agentService.getEnabledAgent(1L)).thenReturn(agent);
        when(baseMapper.insert(any(AiSession.class))).thenReturn(1);

        AiSession session = service.createUnattendedSession(1L, null, 9L, 3L, null);

        assertEquals(AiSession.SOURCE_TASK, session.getSource());
        assertEquals("与 小Z 的对话", session.getSessionTitle());
    }

    @Test
    @DisplayName("无人值守追加用户消息")
    void shouldAppendUserMessageUnattended() {
        AiSession session = session(1L, 9L, "0");
        when(baseMapper.selectById(1L)).thenReturn(session);

        AiMessage message = service.appendUserMessageUnattended(1L, "任务内容");

        assertEquals(AiMessage.ROLE_USER, message.getRole());
        verify(messageMapper).insert(any(AiMessage.class));
    }

    @Test
    @DisplayName("落库助手消息与工具日志并累计 Token")
    void shouldPersistAssistantTurn() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiToolCallLog log = new AiToolCallLog();

        AiMessage message = service.persistAssistantTurn(1L, "回答", "reply-1", 120L,
            List.of(log), new ChatTokenUsage(1, 2, 3), 9L, null);

        assertEquals(AiMessage.ROLE_ASSISTANT, message.getRole());
        assertEquals(1, message.getToolCallCount());
        assertEquals(1, message.getPromptTokens());
        assertEquals(2, message.getCompletionTokens());
        assertEquals(3, message.getTotalTokens());
        verify(toolCallLogMapper).insert(any(AiToolCallLog.class));
    }

    @Test
    @DisplayName("落库失败助手消息并截断错误信息")
    void shouldPersistAssistantFailure() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));

        AiMessage message = service.persistAssistantFailure(1L, "e".repeat(1500), 10L);

        assertEquals("1", message.getStatus());
        assertEquals(1000, message.getErrorMsg().length());
    }

    @Test
    @DisplayName("落库待确认助手消息")
    void shouldPersistPendingTurn() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));

        AiMessage message = service.persistPendingTurn(1L, "暂停", "reply", 10L, null, null, 9L, null);

        assertEquals(AiMessage.STATUS_PENDING_CONFIRM, message.getStatus());
        assertEquals(0, message.getToolCallCount());
    }

    @Test
    @DisplayName("追加助手消息时消息不存在抛出异常")
    void shouldRejectAppendAssistantWhenMissing() {
        when(messageMapper.selectById(5L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.appendAssistantTurn(5L, "x", "0", 1L, null, null, null));
    }

    @Test
    @DisplayName("追加助手消息累计内容、耗时与 Token")
    void shouldAppendAssistantTurn() {
        AiMessage exists = new AiMessage();
        exists.setId(5L);
        exists.setSessionId(1L);
        exists.setContent("a");
        exists.setDurationMs(10L);
        exists.setToolCallCount(1);
        exists.setPromptTokens(2);
        exists.setCompletionTokens(3);
        exists.setTotalTokens(5);
        AiMessage updated = new AiMessage();
        updated.setId(5L);
        when(messageMapper.selectById(5L)).thenReturn(exists, updated);
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));

        AiMessage result = service.appendAssistantTurn(5L, "b", AiMessage.STATUS_NORMAL, 5L,
            List.of(new AiToolCallLog()), new ChatTokenUsage(1, 1, 2), null);

        assertSame(updated, result);
        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper).updateById(captor.capture());
        AiMessage update = captor.getValue();
        assertEquals("ab", update.getContent());
        assertEquals(15L, update.getDurationMs());
        assertEquals(2, update.getToolCallCount());
        assertEquals(3, update.getPromptTokens());
        assertEquals(4, update.getCompletionTokens());
        assertEquals(7, update.getTotalTokens());
    }

    @Test
    @DisplayName("重新生成预检：只读最后一轮允许")
    void shouldAllowRegenerateOnReadOnlyLastRound() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));
        AiToolCallLog log = toolLog("query_kb", "SUCCESS");
        // 平台内置只读工具（未登记）不应被误判为写操作
        AiToolCallLog builtin = toolLog("load_skill_through_path", "SUCCESS");
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(log, builtin));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool("query_kb", "查知识库", AiTool.TYPE_READ_ONLY)));

        AiRegenerateCheckVo vo = service.checkRegenerable(1L, 1L);

        assertTrue(vo.isAllowed());
        assertEquals(AiRegenerateCheckVo.CODE_OK, vo.getReasonCode());
        assertTrue(vo.getWriteTools().isEmpty());
    }

    @Test
    @DisplayName("重新生成预检：写工具轮次拦截并回传工具中文名")
    void shouldRejectRegenerateWhenWriteToolUsed() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(toolLog("create_notice", "SUCCESS")));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool("create_notice", "发布通知", "1")));

        AiRegenerateCheckVo vo = service.checkRegenerable(1L, 1L);

        assertFalse(vo.isAllowed());
        assertEquals(AiRegenerateCheckVo.CODE_WRITE_TOOL_USED, vo.getReasonCode());
        assertEquals("发布通知", vo.getWriteTools().get(0).getToolName());
    }

    @Test
    @DisplayName("重新生成预检：写工具 ERROR / INTERRUPTED 也拦截，DENIED 放行")
    void shouldApplyWriteToolStateRules() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool("create_notice", "发布通知", "1")));

        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(toolLog("create_notice", "ERROR")));
        assertFalse(service.checkRegenerable(1L, 1L).isAllowed());

        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(toolLog("create_notice", "INTERRUPTED")));
        assertFalse(service.checkRegenerable(1L, 1L).isAllowed());

        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(toolLog("create_notice", "DENIED")));
        assertTrue(service.checkRegenerable(1L, 1L).isAllowed());
    }

    @Test
    @DisplayName("重新生成预检：未登记工具按写操作拦截")
    void shouldRejectRegenerateWhenToolUnknown() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(toolLog("mcp_ab12", "SUCCESS")));
        when(toolMapper.selectList(any())).thenReturn(List.of());

        AiRegenerateCheckVo vo = service.checkRegenerable(1L, 1L);

        assertFalse(vo.isAllowed());
        assertEquals(AiRegenerateCheckVo.CODE_UNKNOWN_TOOL_USED, vo.getReasonCode());
        assertTrue(vo.getWriteTools().get(0).isUnknown());
    }

    @Test
    @DisplayName("重新生成预检：非最后一轮拒绝")
    void shouldRejectRegenerateWhenNotLastRound() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        AiMessage nextUser = message(3L, 1L, AiMessage.ROLE_USER, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant, nextUser));

        AiRegenerateCheckVo vo = service.checkRegenerable(1L, 1L);

        assertFalse(vo.isAllowed());
        assertEquals(AiRegenerateCheckVo.CODE_ROUND_NOT_LAST, vo.getReasonCode());
    }

    @Test
    @DisplayName("重新生成预检：待人工确认轮次拒绝")
    void shouldRejectRegenerateWhenPendingConfirm() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        assistant.setStatus(AiMessage.STATUS_PENDING_CONFIRM);
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));

        AiRegenerateCheckVo vo = service.checkRegenerable(1L, 1L);

        assertFalse(vo.isAllowed());
        assertEquals(AiRegenerateCheckVo.CODE_ROUND_PENDING_CONFIRM, vo.getReasonCode());
    }

    @Test
    @DisplayName("删除本轮问答：写操作轮次拒绝，被替换的旧回答按主键删除")
    void shouldGuardDeleteByWriteToolsAndDeleteOnlyAnswers() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));

        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(toolLog("create_notice", "SUCCESS")));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool("create_notice", "发布通知", "1")));
        assertThrows(ServiceException.class, () -> service.deleteChatRound(1L, 1L));

        service.deleteAnswerMessages(1L, 9L, List.of(2L));
        ArgumentCaptor<Collection<?>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(messageMapper).deleteByIds(captor.capture());
        assertFalse(captor.getValue().contains(1L));
        assertTrue(captor.getValue().contains(2L));
    }

    @Test
    @DisplayName("删除一轮问答：删除用户消息及其后助手消息")
    void shouldDeleteChatRound() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));
        when(messageMapper.deleteByIds(any())).thenReturn(1);

        assertTrue(service.deleteChatRound(1L, 1L));
        verify(messageMapper).deleteByIds(any());
    }

    @Test
    @DisplayName("删除一轮问答：回答仍在生成中时拒绝")
    void shouldRejectDeleteWhenStreaming() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        AiMessage user = message(1L, 1L, AiMessage.ROLE_USER, "0");
        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "1");
        when(messageMapper.selectById(1L)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));

        assertThrows(ServiceException.class, () -> service.deleteChatRound(1L, 1L));
    }

    @Test
    @DisplayName("删除一轮问答：非用户消息或归属不符时拒绝")
    void shouldRejectDeleteInvalidTarget() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        assertThrows(ServiceException.class, () -> service.deleteChatRound(1L, null));

        AiMessage assistant = message(2L, 1L, AiMessage.ROLE_ASSISTANT, "0");
        when(messageMapper.selectById(2L)).thenReturn(assistant);
        assertThrows(ServiceException.class, () -> service.deleteChatRound(1L, 2L));

        AiMessage other = message(3L, 2L, AiMessage.ROLE_USER, "0");
        when(messageMapper.selectById(3L)).thenReturn(other);
        assertThrows(ServiceException.class, () -> service.deleteChatRound(1L, 3L));
    }

    @Test
    @DisplayName("查询消息与待确认消息")
    void shouldQueryMessages() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        when(messageMapper.selectVoList(any())).thenReturn(List.of(new AiMessageVo()));

        assertEquals(1, service.queryMessages(1L).size());
        assertEquals(1, service.queryPendingMessages(1L).size());
    }

    @Test
    @DisplayName("关闭、重命名与置顶会话")
    void shouldModifySession() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        when(baseMapper.updateById(any(AiSession.class))).thenReturn(1);

        assertTrue(service.closeSession(1L));
        assertTrue(service.renameSession(1L, "新标题"));
        assertTrue(service.toggleTop(1L, Boolean.TRUE));
    }

    @Test
    @DisplayName("重命名会话标题非法时拒绝")
    void shouldRejectInvalidRename() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        assertThrows(ServiceException.class, () -> service.renameSession(1L, "   "));
        assertThrows(ServiceException.class, () -> service.renameSession(1L, "a".repeat(201)));
    }

    @Test
    @DisplayName("分页查询会话回填展示名")
    void shouldQueryPageAndMySessions() {
        AiSessionVo vo = sessionVo();
        vo.setId(1L);
        Page<AiSessionVo> page = new Page<>(1, 10);
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiSessionVo> mine = service.queryMySessions(new AiSessionBo(), new PageQuery(10, 1));
        PageResult<AiSessionVo> pageResult = service.queryPageList(new AiSessionBo(), new PageQuery(10, 1));

        assertEquals(1L, mine.getTotal());
        assertEquals(1L, pageResult.getTotal());
    }

    @Test
    @DisplayName("导出数据映射状态文案")
    void shouldBuildExportData() {
        AiSessionVo vo = sessionVo();
        vo.setId(1L);
        vo.setStatus("1");
        vo.setCreateTime(LocalDateTime.now());
        when(baseMapper.selectVoList(any())).thenReturn(List.of(vo));

        List<AiSessionExportVo> rows = service.buildExportData(new AiSessionBo());

        assertEquals(1, rows.size());
        assertEquals("已结束", rows.get(0).getStatusText());
    }

    @Test
    @DisplayName("查询会话涉及智能体选项")
    void shouldQueryAgentOptions() {
        when(baseMapper.selectObjs(any())).thenReturn(List.of());
        assertTrue(service.queryAgentOptions().isEmpty());

        when(baseMapper.selectObjs(any())).thenReturn(java.util.Arrays.<Object>asList(1L));
        when(agentService.queryEntityVos(any())).thenReturn(List.of());
        assertTrue(service.queryAgentOptions().isEmpty());
    }

    @Test
    @DisplayName("批量终止会话：空集合与不匹配时拒绝")
    void shouldValidateCloseWithIds() {
        assertThrows(ServiceException.class, () -> service.closeWithValidByIds(List.of()));
        when(baseMapper.selectByIds(any())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.closeWithValidByIds(List.of(1L)));
    }

    @Test
    @DisplayName("批量终止会话成功")
    void shouldCloseWithIds() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(session(1L, 9L, "0")));
        when(baseMapper.update(any(AiSession.class), any())).thenReturn(1);

        assertTrue(service.closeWithValidByIds(List.of(1L)));
    }

    @Test
    @DisplayName("批量删除会话：清理消息与模型记忆")
    void shouldDeleteSessions() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(session(1L, 9L, "0")));
        when(baseMapper.deleteByIds(any())).thenReturn(1);

        assertTrue(service.deleteWithValidByIds(List.of(1L)));

        verify(messageMapper).delete(any());
        verify(aiAgentStateStore).delete("9", "1");
    }

    @Test
    @DisplayName("删除会话：主键数量不一致时拒绝")
    void shouldRejectDeleteSessionsMismatch() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L)));
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of()));
    }

    @Test
    @DisplayName("查询会话消息与工具调用明细")
    void shouldQuerySessionMessagesAndToolCalls() {
        when(baseMapper.selectById(1L)).thenReturn(session(1L, 9L, "0"));
        when(messageMapper.selectVoList(any())).thenReturn(List.of(new AiMessageVo()));
        when(toolCallLogMapper.selectVoList(any())).thenReturn(List.of(new AiToolCallLogVo()));

        assertEquals(1, service.querySessionMessages(1L).size());
        assertEquals(1, service.queryOwnedToolCalls(1L).size());
    }

    private static AiSessionVo sessionVo() {
        AiSessionVo vo = new AiSessionVo();
        vo.setUserId(9L);
        vo.setDeptId(3L);
        return vo;
    }

    private static AiSession session(Long id, Long userId, String status) {
        AiSession session = new AiSession();
        session.setId(id);
        session.setUserId(userId);
        session.setStatus(status);
        session.setMessageCount(0);
        return session;
    }

    private static AiMessage message(Long id, Long sessionId, String role, String streaming) {
        AiMessage message = new AiMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setStreaming(streaming);
        return message;
    }

    private static AiToolCallLog toolLog(String toolName, String state) {
        AiToolCallLog log = new AiToolCallLog();
        log.setToolName(toolName);
        log.setState(state);
        return log;
    }

    private static AiTool tool(String toolCode, String toolName, String toolType) {
        AiTool tool = new AiTool();
        tool.setToolCode(toolCode);
        tool.setToolName(toolName);
        tool.setToolType(toolType);
        return tool;
    }

}