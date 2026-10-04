package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiMessageAuditBo;
import org.dromara.ai.domain.bo.AiToolCallAuditBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageAuditExportVo;
import org.dromara.ai.domain.vo.AiMessageAuditVo;
import org.dromara.ai.domain.vo.AiToolCallAuditVo;
import org.dromara.ai.domain.vo.AiToolCallStatVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiRouteExpertCallMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.mapper.AiToolCallLogMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AiAuditServiceImpl} 单元测试：消息/工具调用审计的分页、导出、统计聚合与智能体选项解析。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI 会话与工具调用审计 Service")
class AiAuditServiceImplTest {

    @Mock
    private AiMessageMapper messageMapper;

    @Mock
    private AiToolCallLogMapper toolCallLogMapper;

    @Mock
    private AiSessionMapper sessionMapper;

    @Mock
    private IAiAgentService agentService;

    @Mock
    private AiRouteExpertCallMapper expertCallMapper;

    @Mock
    private UserService userService;

    @InjectMocks
    private AiAuditServiceImpl service;

    /**
     * 构造会话实体。
     */
    private AiSession session(Long id, String title, Long agentId, Long userId) {
        AiSession session = new AiSession();
        session.setId(id);
        session.setSessionTitle(title);
        session.setAgentId(agentId);
        session.setUserId(userId);
        return session;
    }

    /**
     * 构造智能体实体（匿名子类，AiAgent 为抽象类）。
     */
    private AiAgent agent(Long id, String name) {
        AiAgent agent = new AiAgent() {
        };
        agent.setId(id);
        agent.setAgentName(name);
        return agent;
    }

    /**
     * 构造智能体视图对象。
     */
    private AiAgentVo agentVo(Long id) {
        AiAgentVo vo = new AiAgentVo();
        vo.setId(id);
        return vo;
    }

    @Test
    @DisplayName("消息审计分页：回填会话、智能体与用户")
    void shouldQueryMessagePage() {
        AiMessage message = new AiMessage();
        message.setId(1L);
        message.setSessionId(10L);
        message.setRole("user");
        message.setStatus("0");
        message.setContent("hi");
        Page<AiMessage> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(message)));
        page.setTotal(1L);
        when(messageMapper.selectPage(any(), any())).thenReturn(page);
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session(10L, "标题", 20L, 30L)));
        when(agentService.queryEntities(any())).thenReturn(List.of(agent(20L, "A")));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(30L, "U"));

        PageResult<AiMessageAuditVo> result = service.queryMessagePage(new AiMessageAuditBo(), new PageQuery(1, 10));

        assertEquals(1L, result.getTotal());
        AiMessageAuditVo row = result.getRows().iterator().next();
        assertEquals("标题", row.getSessionTitle());
        assertEquals("A", row.getAgentName());
        assertEquals("U", row.getUserName());
    }

    @Test
    @DisplayName("消息审计分页：会话维度未命中返回空页")
    void shouldReturnEmptyMessagePageWhenSessionFilterMisses() {
        AiMessageAuditBo bo = new AiMessageAuditBo();
        bo.setAgentId(1L);
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        PageResult<AiMessageAuditVo> result = service.queryMessagePage(bo, new PageQuery(1, 10));

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(messageMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("消息审计导出：角色/状态文案与时间格式化")
    void shouldBuildMessageExport() {
        AiMessage message = new AiMessage();
        message.setId(1L);
        message.setSessionId(10L);
        message.setRole("user");
        message.setStatus("1");
        message.setContent("hi");
        message.setErrorMsg("err");
        message.setCreateTime(LocalDateTime.of(2024, 1, 2, 3, 4, 5));
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session(10L, "标题", 20L, 30L)));
        when(agentService.queryEntities(any())).thenReturn(List.of(agent(20L, "A")));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(30L, "U"));

        List<AiMessageAuditExportVo> result = service.buildMessageExport(new AiMessageAuditBo());

        assertEquals(1, result.size());
        assertEquals("用户", result.get(0).getRoleText());
        assertEquals("失败", result.get(0).getStatusText());
        assertEquals("2024-01-02 03:04:05", result.get(0).getCreateTime());
    }

    @Test
    @DisplayName("消息审计导出：会话维度未命中返回空列表")
    void shouldReturnEmptyExportWhenSessionFilterMisses() {
        AiMessageAuditBo bo = new AiMessageAuditBo();
        bo.setUserId(1L);
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.buildMessageExport(bo).isEmpty());
    }

    @Test
    @DisplayName("工具调用审计分页：明细智能体优先于会话智能体")
    void shouldQueryToolCallPage() {
        AiToolCallLog log = new AiToolCallLog();
        log.setId(1L);
        log.setSessionId(10L);
        log.setAgentId(25L);
        log.setToolName("t");
        Page<AiToolCallLog> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(log)));
        page.setTotal(1L);
        when(toolCallLogMapper.selectPage(any(), any())).thenReturn(page);
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session(10L, "标题", 20L, 30L)));
        when(agentService.queryEntities(any())).thenReturn(List.of(agent(20L, "A"), agent(25L, "E")));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(30L, "U"));

        PageResult<AiToolCallAuditVo> result = service.queryToolCallPage(new AiToolCallAuditBo(), new PageQuery(1, 10));

        assertEquals(1L, result.getTotal());
        AiToolCallAuditVo row = result.getRows().iterator().next();
        assertEquals("E", row.getAgentName());
        assertEquals("标题", row.getSessionTitle());
        assertEquals("U", row.getUserName());
    }

    @Test
    @DisplayName("工具调用审计分页：归属用户未命中会话返回空页")
    void shouldReturnEmptyToolCallPageWhenOwnerMissing() {
        AiToolCallAuditBo bo = new AiToolCallAuditBo();
        bo.setOwnerUserId(9L);
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        PageResult<AiToolCallAuditVo> result = service.queryToolCallPage(bo, new PageQuery(1, 10));

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRows().isEmpty());
    }

    @Test
    @DisplayName("工具调用统计：按工具聚合并计算失败率与平均耗时")
    void shouldQueryToolCallStats() {
        AiToolCallAuditBo bo = new AiToolCallAuditBo();
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(
            toolLog("t", "SUCCESS", 100L),
            toolLog("t", "ERROR", 50L),
            toolLog("", null, null)));

        List<AiToolCallStatVo> stats = service.queryToolCallStats(bo);

        assertEquals(2, stats.size());
        AiToolCallStatVo t = stats.stream().filter(s -> "t".equals(s.getToolName())).findFirst().orElseThrow();
        assertEquals(2L, t.getTotalCount());
        assertEquals(1L, t.getSuccessCount());
        assertEquals(1L, t.getFailCount());
        assertEquals(75L, t.getAvgDurationMs());
        assertEquals(0, t.getFailRate().compareTo(new java.math.BigDecimal("50.00")));

        AiToolCallStatVo unknown = stats.stream().filter(s -> "unknown".equals(s.getToolName())).findFirst().orElseThrow();
        assertEquals(1L, unknown.getTotalCount());
    }

    @Test
    @DisplayName("工具调用统计：归属用户未命中会话返回空列表")
    void shouldReturnEmptyStatsWhenOwnerMissing() {
        AiToolCallAuditBo bo = new AiToolCallAuditBo();
        bo.setOwnerUserId(9L);
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.queryToolCallStats(bo).isEmpty());
    }

    @Test
    @DisplayName("引用智能体选项：合并工具明细与专家调用并按主键升序")
    void shouldQueryReferencedAgentOptions() {
        AiToolCallLog log = new AiToolCallLog();
        log.setAgentId(1L);
        AiToolCallLog historyLog = new AiToolCallLog();
        historyLog.setAgentId(null);
        AiRouteExpertCall call = new AiRouteExpertCall();
        call.setExpertAgentId(2L);
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(log, historyLog));
        when(expertCallMapper.selectList(any())).thenReturn(List.of(call));
        when(agentService.queryEntityVos(any())).thenReturn(new ArrayList<>(List.of(agentVo(2L), agentVo(1L))));

        List<AiAgentVo> options = service.queryReferencedAgentOptions();

        assertEquals(List.of(1L, 2L), options.stream().map(AiAgentVo::getId).toList());
    }

    @Test
    @DisplayName("引用智能体选项：无任何引用返回空列表")
    void shouldReturnEmptyAgentOptions() {
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());
        when(expertCallMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.queryReferencedAgentOptions().isEmpty());
    }

    /**
     * 构造工具调用明细行。
     */
    private AiToolCallLog toolLog(String toolName, String state, Long durationMs) {
        AiToolCallLog log = new AiToolCallLog();
        log.setToolName(toolName);
        log.setState(state);
        log.setDurationMs(durationMs);
        return log;
    }

}