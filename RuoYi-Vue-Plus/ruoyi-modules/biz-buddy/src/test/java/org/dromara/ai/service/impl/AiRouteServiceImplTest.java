package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiRouteRunBo;
import org.dromara.ai.domain.vo.AiRouteExpertCallVo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiRouteRunStatsVo;
import org.dromara.ai.domain.vo.AiRouteRunVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiRouteExpertCallMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRouteServiceImpl} 单元测试：会话编排明细、分页回填与统计聚合。
 */
@DisplayName("AI 编排运行查询服务")
class AiRouteServiceImplTest {

    private final AiRouteRunMapper routeRunMapper = mock(AiRouteRunMapper.class);
    private final AiRouteExpertCallMapper expertCallMapper = mock(AiRouteExpertCallMapper.class);
    private final AiSessionMapper sessionMapper = mock(AiSessionMapper.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final IAiSessionService sessionService = mock(IAiSessionService.class);
    private final IAiAgentService agentService = mock(IAiAgentService.class);
    private final UserService userService = mock(UserService.class);
    private final AiRouteServiceImpl service = new AiRouteServiceImpl(routeRunMapper, expertCallMapper, sessionMapper,
        messageMapper, sessionService, agentService, userService);

    @Test
    @DisplayName("会话无编排记录时返回空列表")
    void shouldReturnEmptyWhenSessionHasNoRuns() {
        when(routeRunMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.querySessionRuns(1L).isEmpty());
        verify(sessionService).getOwnedSession(1L);
    }

    @Test
    @DisplayName("会话编排明细回填专家调用与 Token")
    void shouldQuerySessionRuns() {
        AiRouteRun run = new AiRouteRun();
        run.setId(10L);
        run.setSessionId(1L);
        run.setMessageId(100L);
        run.setEntryAgentId(5L);
        when(routeRunMapper.selectList(any())).thenReturn(List.of(run));

        AiRouteExpertCallVo call = new AiRouteExpertCallVo();
        call.setId(1L);
        call.setRouteRunId(10L);
        call.setExpertAgentId(5L);
        call.setPromptTokens(3L);
        call.setCompletionTokens(4L);
        when(expertCallMapper.selectVoList(any())).thenReturn(List.of(call));

        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(5L);
        expert.setAgentName("财务专家");
        expert.setAgentCode("fin");
        when(agentService.queryEntities(any())).thenReturn(List.of(expert));

        AiMessage message = new AiMessage();
        message.setId(100L);
        message.setTotalTokens(7);
        message.setPromptTokens(2);
        message.setCompletionTokens(5);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        List<AiRouteRunDetailVo> result = service.querySessionRuns(1L);

        assertEquals(1, result.size());
        AiRouteRunDetailVo detail = result.get(0);
        assertEquals("财务专家", detail.getExpertCalls().get(0).getExpertAgentName());
        assertEquals(7L, detail.getExpertTokens());
        assertEquals(7L, detail.getEntryTokens());
        assertEquals(14L, detail.getTotalTokens());
    }

    @Test
    @DisplayName("分页查询回填显示字段与 Token 汇总")
    void shouldQueryPageList() {
        AiRouteRunVo vo = new AiRouteRunVo();
        vo.setId(10L);
        vo.setEntryAgentId(5L);
        vo.setSessionId(1L);
        vo.setUserId(9L);
        vo.setMessageId(100L);
        Page<AiRouteRunVo> page = new Page<>(1, 10);
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(routeRunMapper.selectVoPage(any(), any())).thenReturn(page);

        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(5L);
        agent.setAgentName("入口A");
        agent.setAgentCode("entry_a");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));

        AiSession session = new AiSession();
        session.setId(1L);
        session.setSessionTitle("会话标题");
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session));

        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(9L, "张三"));
        when(expertCallMapper.selectVoList(any())).thenReturn(List.of());

        AiMessage message = new AiMessage();
        message.setId(100L);
        message.setTotalTokens(7);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        PageResult<AiRouteRunVo> result = service.queryPageList(new AiRouteRunBo(), new PageQuery(10, 1));

        AiRouteRunVo row = result.getRows().iterator().next();
        assertEquals("入口A", row.getEntryAgentName());
        assertEquals("entry_a", row.getEntryAgentCode());
        assertEquals("会话标题", row.getSessionTitle());
        assertEquals("张三", row.getUserName());
        assertEquals(7L, row.getEntryTokens());
        assertEquals(7L, row.getTotalTokens());
    }

    @Test
    @DisplayName("详情不存在时抛出异常")
    void shouldThrowWhenRunMissing() {
        when(routeRunMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.queryById(1L));
    }

    @Test
    @DisplayName("查询详情回填会话、入口智能体、用户与 Token")
    void shouldQueryDetail() {
        AiRouteRun run = new AiRouteRun();
        run.setId(10L);
        run.setSessionId(1L);
        run.setMessageId(100L);
        run.setEntryAgentId(5L);
        run.setUserId(9L);
        run.setAction(AiRouteRun.ACTION_ROUTE);
        when(routeRunMapper.selectById(10L)).thenReturn(run);

        AiRouteExpertCallVo call = new AiRouteExpertCallVo();
        call.setId(1L);
        call.setRouteRunId(10L);
        call.setExpertAgentId(5L);
        call.setPromptTokens(3L);
        call.setCompletionTokens(4L);
        when(expertCallMapper.selectVoList(any())).thenReturn(List.of(call));

        AiMessage message = new AiMessage();
        message.setId(100L);
        message.setTotalTokens(7);
        message.setPromptTokens(2);
        message.setCompletionTokens(5);
        when(messageMapper.selectById(100L)).thenReturn(message);

        AiExpertAgent entry = new AiExpertAgent();
        entry.setId(5L);
        entry.setAgentName("小Z");
        when(agentService.queryEntity(5L)).thenReturn(entry);

        AiSession session = new AiSession();
        session.setId(1L);
        session.setSessionTitle("标题");
        when(sessionMapper.selectById(1L)).thenReturn(session);

        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(9L, "张三"));

        AiRouteRunDetailVo detail = service.queryById(10L);

        assertEquals("小Z", detail.getEntryAgentName());
        assertEquals("标题", detail.getSessionTitle());
        assertEquals("张三", detail.getUserName());
        assertEquals(2L, detail.getEntryPromptTokens());
        assertEquals(5L, detail.getEntryCompletionTokens());
        assertEquals(14L, detail.getTotalTokens());
    }

    @Test
    @DisplayName("统计聚合：计数、命中率、降级率与 Token 汇总")
    void shouldComputeStats() {
        AiRouteRun self = run(10L, AiRouteRun.ACTION_SELF, AiRouteRun.STATUS_SUCCESS, 1, 100L, 100L);
        AiRouteRun route = run(11L, AiRouteRun.ACTION_ROUTE, AiRouteRun.STATUS_DEGRADED, 2, 200L, 101L);
        AiRouteRun pinned = run(12L, AiRouteRun.ACTION_PINNED, AiRouteRun.STATUS_RUNNING, 0, null, null);
        when(routeRunMapper.selectList(any())).thenReturn(List.of(self, route, pinned));

        AiRouteExpertCall call = new AiRouteExpertCall();
        call.setId(1L);
        call.setPromptTokens(5L);
        call.setCompletionTokens(5L);
        when(expertCallMapper.selectList(any())).thenReturn(List.of(call));

        AiMessage m1 = new AiMessage();
        m1.setId(100L);
        m1.setTotalTokens(7);
        AiMessage m2 = new AiMessage();
        m2.setId(101L);
        m2.setTotalTokens(3);
        when(messageMapper.selectList(any())).thenReturn(List.of(m1, m2));

        AiRouteRunStatsVo stats = service.queryStats(new AiRouteRunBo());

        assertEquals(3L, stats.getTotalCount());
        assertEquals(1L, stats.getSelfCount());
        assertEquals(2L, stats.getRouteCount());
        assertEquals(1L, stats.getPinnedCount());
        assertEquals(1L, stats.getRunningCount());
        assertEquals(1L, stats.getSuccessCount());
        assertEquals(1L, stats.getDegradedCount());
        assertEquals(3L, stats.getExpertCallTotal());
        assertEquals(300L, stats.getTotalDurationMs());
        assertEquals(100L, stats.getAvgDurationMs());
        assertEquals(66.7, stats.getRouteHitRate());
        assertEquals(50.0, stats.getDegradeRate());
        assertEquals(5L, stats.getExpertPromptTokens());
        assertEquals(5L, stats.getExpertCompletionTokens());
        assertEquals(10L, stats.getEntryTokens());
        assertEquals(20L, stats.getTotalTokens());
    }

    @Test
    @DisplayName("统计聚合：无数据时比率与均值为空")
    void shouldComputeEmptyStats() {
        when(routeRunMapper.selectList(any())).thenReturn(List.of());

        AiRouteRunStatsVo stats = service.queryStats(new AiRouteRunBo());

        assertEquals(0L, stats.getTotalCount());
        assertNull(stats.getRouteHitRate());
        assertNull(stats.getDegradeRate());
        assertNull(stats.getAvgDurationMs());
        assertEquals(0L, stats.getTotalTokens());
    }

    @Test
    @DisplayName("统计可携带专家子查询与时间范围筛选")
    void shouldBuildStatsWrapperWithExpertFilter() {
        AiRouteRunBo bo = new AiRouteRunBo();
        bo.setExpertAgentId(7L);
        bo.setAction(AiRouteRun.ACTION_ROUTE);
        bo.setSessionId(1L);
        when(routeRunMapper.selectList(any())).thenReturn(List.of());

        service.queryStats(bo);

        verify(routeRunMapper).selectList(any());
    }

    private static AiRouteRun run(Long id, String action, String status, int expertCallCount, Long duration, Long messageId) {
        AiRouteRun run = new AiRouteRun();
        run.setId(id);
        run.setAction(action);
        run.setStatus(status);
        run.setExpertCallCount(expertCallCount);
        run.setDurationMs(duration);
        run.setMessageId(messageId);
        return run;
    }

}