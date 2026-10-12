package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiRouteRunBo;
import org.dromara.ai.domain.vo.AiRouteExpertCallVo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiRouteRunStatsVo;
import org.dromara.ai.domain.vo.AiRouteRunVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
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

import java.math.BigDecimal;
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
    private final AiModelConfigMapper modelConfigMapper = mock(AiModelConfigMapper.class);
    private final IAiSessionService sessionService = mock(IAiSessionService.class);
    private final IAiAgentService agentService = mock(IAiAgentService.class);
    private final UserService userService = mock(UserService.class);
    private final AiRouteServiceImpl service = new AiRouteServiceImpl(routeRunMapper, expertCallMapper, sessionMapper,
        messageMapper, modelConfigMapper, sessionService, agentService, userService);

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
        when(agentService.queryEntities(any())).thenReturn(List.of(entry));

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
    @DisplayName("查询详情回填结构化决策判定列（影子 / 闸门留痕）")
    void shouldQueryDetailWithJudgeFields() {
        AiRouteRun run = new AiRouteRun();
        run.setId(11L);
        run.setAction(AiRouteRun.ACTION_ROUTE);
        run.setDecisionSource("L");
        run.setJudgeMode("S");
        run.setJudgeChoice("notice_admin_expert");
        run.setJudgeConfidence(0.86D);
        run.setJudgeLatencyMs(62);
        run.setJudgeTokens(159);
        run.setJudgeError(null);
        when(routeRunMapper.selectById(11L)).thenReturn(run);

        AiRouteRunDetailVo detail = service.queryById(11L);

        assertEquals("L", detail.getDecisionSource());
        assertEquals("S", detail.getJudgeMode());
        assertEquals("notice_admin_expert", detail.getJudgeChoice());
        assertEquals(0.86D, detail.getJudgeConfidence());
        assertEquals(62, detail.getJudgeLatencyMs());
        assertEquals(159, detail.getJudgeTokens());
        assertNull(detail.getJudgeError());
    }

    @Test
    @DisplayName("详情费用折算：按入口与各专家模型单价计算")
    void shouldComputeCostByModelPrice() {
        AiRouteRun run = new AiRouteRun();
        run.setId(10L);
        run.setSessionId(1L);
        run.setMessageId(100L);
        run.setEntryAgentId(5L);
        run.setAction(AiRouteRun.ACTION_ROUTE);
        when(routeRunMapper.selectById(10L)).thenReturn(run);

        AiRouteExpertCallVo call = new AiRouteExpertCallVo();
        call.setId(1L);
        call.setRouteRunId(10L);
        call.setExpertAgentId(6L);
        call.setPromptTokens(1000L);
        call.setCompletionTokens(2000L);
        when(expertCallMapper.selectVoList(any())).thenReturn(List.of(call));

        AiMessage message = new AiMessage();
        message.setId(100L);
        message.setPromptTokens(1000);
        message.setCompletionTokens(1000);
        message.setTotalTokens(2000);
        when(messageMapper.selectById(100L)).thenReturn(message);

        AiExpertAgent entryAgent = new AiExpertAgent();
        entryAgent.setId(5L);
        entryAgent.setModelId(1L);
        AiExpertAgent expertAgent = new AiExpertAgent();
        expertAgent.setId(6L);
        expertAgent.setModelId(2L);
        when(agentService.queryEntities(any())).thenReturn(List.of(entryAgent, expertAgent));

        AiModelConfig entryModel = new AiModelConfig();
        entryModel.setId(1L);
        entryModel.setInputPrice(new BigDecimal("0.001"));
        entryModel.setOutputPrice(new BigDecimal("0.002"));
        AiModelConfig expertModel = new AiModelConfig();
        expertModel.setId(2L);
        expertModel.setInputPrice(new BigDecimal("0.01"));
        expertModel.setOutputPrice(new BigDecimal("0.02"));
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(entryModel, expertModel));

        AiRouteRunDetailVo detail = service.queryById(10L);

        // 入口轮：1000/1000×0.001 + 1000/1000×0.002 = 0.003
        assertEquals(0, new BigDecimal("0.003").compareTo(detail.getEntryCost()));
        // 专家轮：1000/1000×0.01 + 2000/1000×0.02 = 0.05
        assertEquals(0, new BigDecimal("0.05").compareTo(detail.getExpertCost()));
        assertEquals(0, new BigDecimal("0.053").compareTo(detail.getTotalCost()));
        assertTrue(detail.getPriceConfigured());
        assertEquals(0, new BigDecimal("0.05").compareTo(detail.getExpertCalls().get(0).getCost()));
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