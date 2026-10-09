package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiPermissionDecisionLog;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiPermissionDecisionBo;
import org.dromara.ai.domain.vo.AiPermissionDecisionVo;
import org.dromara.ai.mapper.AiPermissionDecisionLogMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiPermissionDecisionServiceImpl} 单元测试：决策日志写入、确认回填与分页回填展示名。
 */
@DisplayName("AI 工具权限决策日志服务")
class AiPermissionDecisionServiceImplTest {

    private final AiPermissionDecisionLogMapper decisionLogMapper = mock(AiPermissionDecisionLogMapper.class);
    private final AiSessionMapper sessionMapper = mock(AiSessionMapper.class);
    private final IAiAgentService agentService = mock(IAiAgentService.class);
    private final UserService userService = mock(UserService.class);
    private final AiPermissionDecisionServiceImpl service =
        new AiPermissionDecisionServiceImpl(decisionLogMapper, sessionMapper, agentService, userService);

    @Test
    @DisplayName("记录需人工确认决策写入完整字段")
    void shouldRecordAskDecision() {
        service.recordAsk(1L, 2L, 3L, 4L, "query_order", "查询订单", "{}");

        ArgumentCaptor<AiPermissionDecisionLog> captor = ArgumentCaptor.forClass(AiPermissionDecisionLog.class);
        verify(decisionLogMapper).insert(captor.capture());
        AiPermissionDecisionLog entity = captor.getValue();
        assertEquals(AiPermissionDecisionLog.DECISION_ASK, entity.getDecision());
        assertEquals(AiPermissionDecisionLog.SOURCE_AGENT_TOOL, entity.getDecisionSource());
        assertEquals("query_order", entity.getMatchedRule());
        assertEquals(2L, entity.getMessageId());
        assertTrue(entity.getReason().contains("需人工确认"));
    }

    @Test
    @DisplayName("记录允许决策携带执行耗时")
    void shouldRecordAllowDecision() {
        service.recordAllow(1L, 2L, 3L, 4L, "query_order", "查询订单", "{}", 88L);

        ArgumentCaptor<AiPermissionDecisionLog> captor = ArgumentCaptor.forClass(AiPermissionDecisionLog.class);
        verify(decisionLogMapper).insert(captor.capture());
        AiPermissionDecisionLog entity = captor.getValue();
        assertEquals(AiPermissionDecisionLog.DECISION_ALLOW, entity.getDecision());
        assertEquals(88L, entity.getDurationMs());
    }

    @Test
    @DisplayName("日志写入异常不打断主流程")
    void shouldSwallowWriteFailure() {
        when(decisionLogMapper.insert(any(AiPermissionDecisionLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.recordAsk(1L, 2L, 3L, 4L, "t", "n", "{}"));
    }

    @Test
    @DisplayName("消息主键为空时不更新确认结果")
    void shouldSkipApplyConfirmWhenMessageIdNull() {
        service.applyConfirm(null, true, true, null);
        verify(decisionLogMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("确认并记住时回填确认与记住来源")
    void shouldApplyConfirmApprovedRemember() {
        service.applyConfirm(2L, true, true, null);

        ArgumentCaptor<AiPermissionDecisionLog> captor = ArgumentCaptor.forClass(AiPermissionDecisionLog.class);
        verify(decisionLogMapper).update(captor.capture(), any());
        AiPermissionDecisionLog update = captor.getValue();
        assertEquals(AiPermissionDecisionLog.CONFIRM_APPROVED, update.getConfirmed());
        assertEquals(AiPermissionDecisionLog.CONFIRM_REMEMBER, update.getConfirmSource());
    }

    @Test
    @DisplayName("拒绝且仅本次时回填拒绝与单次来源")
    void shouldApplyConfirmRejectedOnce() {
        service.applyConfirm(2L, false, false, null);

        ArgumentCaptor<AiPermissionDecisionLog> captor = ArgumentCaptor.forClass(AiPermissionDecisionLog.class);
        verify(decisionLogMapper).update(captor.capture(), any());
        AiPermissionDecisionLog update = captor.getValue();
        assertEquals(AiPermissionDecisionLog.CONFIRM_REJECTED, update.getConfirmed());
        assertEquals(AiPermissionDecisionLog.CONFIRM_ONCE, update.getConfirmSource());
    }

    @Test
    @DisplayName("拒绝并带原因时把原因写入决策说明")
    void shouldApplyConfirmRejectedWithReason() {
        service.applyConfirm(2L, false, false, "数据敏感");

        ArgumentCaptor<AiPermissionDecisionLog> captor = ArgumentCaptor.forClass(AiPermissionDecisionLog.class);
        verify(decisionLogMapper).update(captor.capture(), any());
        assertEquals("用户拒绝：数据敏感", captor.getValue().getReason());
    }

    @Test
    @DisplayName("分页查询批量回填会话标题、智能体名与用户昵称")
    void shouldQueryPageWithDisplayNames() {
        AiPermissionDecisionLog entity = new AiPermissionDecisionLog();
        entity.setId(1L);
        entity.setSessionId(10L);
        entity.setAgentId(20L);
        entity.setUserId(30L);
        entity.setToolCode("query_order");
        Page<AiPermissionDecisionLog> page = new Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(decisionLogMapper.selectPage(any(), any())).thenReturn(page);

        AiSession session = new AiSession();
        session.setId(10L);
        session.setSessionTitle("会话标题");
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session));

        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(20L);
        agent.setAgentName("小Z");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));

        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(30L, "张三"));

        PageResult<AiPermissionDecisionVo> result = service.queryPage(new AiPermissionDecisionBo(), new PageQuery(10, 1));

        assertEquals(1L, result.getTotal());
        AiPermissionDecisionVo vo = result.getRows().iterator().next();
        assertEquals("会话标题", vo.getSessionTitle());
        assertEquals("小Z", vo.getAgentName());
        assertEquals("张三", vo.getUserName());
    }

    @Test
    @DisplayName("分页查询空结果不触发展示名查询")
    void shouldQueryPageEmpty() {
        Page<AiPermissionDecisionLog> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(decisionLogMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AiPermissionDecisionVo> result = service.queryPage(new AiPermissionDecisionBo(), new PageQuery(10, 1));

        assertEquals(0L, result.getTotal());
        verify(sessionMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("会话/智能体主键为空时不查询下游，用户昵称正常回填")
    void shouldSkipBreakdownForNullIds() {
        AiPermissionDecisionLog entity = new AiPermissionDecisionLog();
        entity.setId(1L);
        entity.setUserId(30L);
        entity.setToolCode("t");
        Page<AiPermissionDecisionLog> page = new Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(decisionLogMapper.selectPage(any(), any())).thenReturn(page);
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(30L, "张三"));

        PageResult<AiPermissionDecisionVo> result = service.queryPage(new AiPermissionDecisionBo(), new PageQuery(10, 1));

        assertEquals(1L, result.getTotal());
        AiPermissionDecisionVo vo = result.getRows().iterator().next();
        assertEquals("t", vo.getToolCode());
        assertEquals("张三", vo.getUserName());
        verify(sessionMapper, never()).selectByIds(any());
        verify(agentService, never()).queryEntities(any());
    }

}