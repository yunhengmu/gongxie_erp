package org.dromara.ai.service.impl;

import cn.hutool.core.lang.Dict;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.bo.AiTraceBo;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.vo.AiTraceVo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.mapper.AiTraceMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AiTraceServiceImpl} 单元测试：链路分页/详情回填、启动落库快照、状态迁移追加与用量累加。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI 链路追踪 Service")
class AiTraceServiceImplTest {

    @Mock
    private AiTraceMapper baseMapper;

    @Mock
    private AiSessionMapper sessionMapper;

    @Mock
    private UserService userService;

    @Mock
    private IAiAgentService agentService;

    @InjectMocks
    private AiTraceServiceImpl service;

    @BeforeAll
    static void initContext() {
        AiServiceTestContext.initialize();
    }

    /**
     * 构造链路视图对象。
     */
    private AiTraceVo vo(Long id, Long sessionId, Long agentId, Long userId) {
        AiTraceVo vo = new AiTraceVo();
        vo.setId(id);
        vo.setSessionId(sessionId);
        vo.setAgentId(agentId);
        vo.setUserId(userId);
        return vo;
    }

    @Test
    @DisplayName("分页查询：回填智能体、会话标题与用户昵称")
    void shouldFillDisplayFieldsOnPage() {
        AiTraceVo vo = vo(1L, 10L, 20L, 30L);
        Page<AiTraceVo> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(vo)));
        page.setTotal(1L);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);

        AiAgent agent = new AiAgent() {
        };
        agent.setId(20L);
        agent.setAgentName("A");
        agent.setAgentCode("a");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));

        AiSession session = new AiSession();
        session.setId(10L);
        session.setSessionTitle("会话标题");
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(30L, "用户"));

        PageResult<AiTraceVo> result = service.queryPageList(new AiTraceBo(), new PageQuery(1, 10));

        assertEquals(1L, result.getTotal());
        assertEquals("A", vo.getAgentName());
        assertEquals("a", vo.getAgentCode());
        assertEquals("会话标题", vo.getSessionTitle());
        assertEquals("用户", vo.getUserName());
    }

    @Test
    @DisplayName("详情查询：不存在抛异常")
    void shouldRejectQueryByIdWhenAbsent() {
        when(baseMapper.selectVoById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.queryById(1L));
    }

    @Test
    @DisplayName("详情查询：解析空上下文与空时间线")
    void shouldQueryByIdWithDetail() {
        AiTraceVo vo = vo(1L, 10L, 20L, null);
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        when(agentService.queryEntities(any())).thenReturn(List.of());
        when(sessionMapper.selectByIds(any())).thenReturn(List.of());

        AiTraceVo result = service.queryById(1L);

        assertTrue(result.getContext().isEmpty());
        assertTrue(result.getTransitions().isEmpty());
    }

    @Test
    @DisplayName("启动链路：默认来源 chat，落库初始状态")
    void shouldStartTraceWithDefaults() {
        try (MockedStatic<JsonUtils> js = mockStatic(JsonUtils.class)) {
            js.when(() -> JsonUtils.toJsonString(any())).thenReturn("[]");
            when(baseMapper.insert(any(AiTrace.class))).thenAnswer(inv -> {
                AiTrace arg = inv.getArgument(0);
                arg.setId(99L);
                return 1;
            });

            Long id = service.start(1L, 2L, 3L, "", null, null);

            assertEquals(99L, id);
            ArgumentCaptor<AiTrace> captor = ArgumentCaptor.forClass(AiTrace.class);
            verify(baseMapper).insert(captor.capture());
            assertEquals(AiTrace.SOURCE_CHAT, captor.getValue().getSource());
            assertEquals(AiTrace.STATUS_RUNNING, captor.getValue().getStatus());
            assertEquals(0L, captor.getValue().getDurationMs());
        }
    }

    @Test
    @DisplayName("启动链路：携带装配快照与父链路")
    void shouldStartTraceWithSnapshot() {
        AgentSnapshot snapshot = new AgentSnapshot(1L, "m", "p", "s", "mem", "sub", Map.of("k", "v"));
        try (MockedStatic<JsonUtils> js = mockStatic(JsonUtils.class)) {
            js.when(() -> JsonUtils.toJsonString(any())).thenReturn("{}");

            service.start(1L, 2L, 3L, "task", snapshot, 5L);

            ArgumentCaptor<AiTrace> captor = ArgumentCaptor.forClass(AiTrace.class);
            verify(baseMapper).insert(captor.capture());
            AiTrace saved = captor.getValue();
            assertEquals("task", saved.getSource());
            assertEquals(5L, saved.getParentTraceId());
            assertEquals("m", saved.getModelCode());
            assertEquals("p", saved.getModelProvider());
            assertEquals("mem", saved.getMemoryModelCode());
            assertNotNull(saved.getContextJson());
        }
    }

    @Test
    @DisplayName("按消息查链路：空入参返回 null，命中与未命中")
    void shouldFindIdByMessage() {
        assertNull(service.findIdByMessage(null, 1L));
        assertNull(service.findIdByMessage(1L, null));

        AiTrace trace = new AiTrace();
        trace.setId(7L);
        when(baseMapper.selectOne(any())).thenReturn(trace);
        assertEquals(7L, service.findIdByMessage(1L, 2L));

        when(baseMapper.selectOne(any())).thenReturn(null);
        assertNull(service.findIdByMessage(1L, 2L));
    }

    @Test
    @DisplayName("追加状态迁移：空入参或实体不存在直接返回")
    void shouldSkipAppendTransition() {
        service.appendTransition(null, "success", null);
        service.appendTransition(1L, "  ", null);
        verifyNoInteractions(baseMapper);

        when(baseMapper.selectById(1L)).thenReturn(null);
        service.appendTransition(1L, "success", null);
        verify(baseMapper, never()).updateById(any(AiTrace.class));
    }

    @Test
    @DisplayName("追加状态迁移：正常追加")
    void shouldAppendTransition() {
        AiTrace entity = new AiTrace();
        entity.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(entity);
        try (MockedStatic<JsonUtils> js = mockStatic(JsonUtils.class)) {
            js.when(() -> JsonUtils.toJsonString(any())).thenReturn("[]");

            service.appendTransition(1L, "success", "完成");

            verify(baseMapper).updateById(any(AiTrace.class));
        }
    }

    @Test
    @DisplayName("追加状态迁移：达到上限不再追加")
    void shouldStopAppendTransitionAtLimit() {
        AiTrace entity = new AiTrace();
        entity.setTransitionsJson("[{}]");
        when(baseMapper.selectById(1L)).thenReturn(entity);
        List<Dict> nodes = IntStream.range(0, 100).mapToObj(i -> new Dict()).toList();
        try (MockedStatic<JsonUtils> js = mockStatic(JsonUtils.class)) {
            js.when(() -> JsonUtils.parseArrayMap(any())).thenReturn(nodes);

            service.appendTransition(1L, "success", null);

            verify(baseMapper, never()).updateById(any(AiTrace.class));
        }
    }

    @Test
    @DisplayName("追加状态迁移：更新异常被吞掉不影响主流程")
    void shouldSwallowAppendTransitionError() {
        AiTrace entity = new AiTrace();
        when(baseMapper.selectById(1L)).thenReturn(entity);
        try (MockedStatic<JsonUtils> js = mockStatic(JsonUtils.class)) {
            js.when(() -> JsonUtils.toJsonString(any())).thenReturn("[]");
            when(baseMapper.updateById(any(AiTrace.class))).thenThrow(new RuntimeException("db"));

            assertDoesNotThrow(() -> service.appendTransition(1L, "success", null));
        }
    }

    @Test
    @DisplayName("回填消息主键：空入参跳过，正常更新")
    void shouldAttachMessage() {
        service.attachMessage(null, 1L);
        service.attachMessage(1L, null);
        verifyNoInteractions(baseMapper);

        service.attachMessage(1L, 2L);
        ArgumentCaptor<AiTrace> captor = ArgumentCaptor.forClass(AiTrace.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals(2L, captor.getValue().getMessageId());
    }

    @Test
    @DisplayName("累加收尾信息：空入参或实体不存在直接返回")
    void shouldSkipAccumulate() {
        service.accumulate(null, "success", 1L, 1, 1, 1, 1, null);
        verifyNoInteractions(baseMapper);

        when(baseMapper.selectById(1L)).thenReturn(null);
        service.accumulate(1L, "success", 1L, 1, 1, 1, 1, null);
        verify(baseMapper, never()).updateById(any(AiTrace.class));
    }

    @Test
    @DisplayName("累加收尾信息：在原值上累加耗时与用量")
    void shouldAccumulate() {
        AiTrace exists = new AiTrace();
        exists.setId(1L);
        exists.setStatus(AiTrace.STATUS_RUNNING);
        exists.setDurationMs(100L);
        exists.setToolCallCount(1);
        exists.setPromptTokens(2);
        exists.setCompletionTokens(3);
        exists.setTotalTokens(5);
        when(baseMapper.selectById(1L)).thenReturn(exists);

        service.accumulate(1L, "success", 50L, 2, 10, 20, 30, null);

        ArgumentCaptor<AiTrace> captor = ArgumentCaptor.forClass(AiTrace.class);
        verify(baseMapper).updateById(captor.capture());
        AiTrace update = captor.getValue();
        assertEquals("success", update.getStatus());
        assertEquals(150L, update.getDurationMs());
        assertEquals(3, update.getToolCallCount());
        assertEquals(12, update.getPromptTokens());
        assertEquals(23, update.getCompletionTokens());
        assertEquals(35, update.getTotalTokens());
        assertNull(update.getErrorMsg());
    }

    @Test
    @DisplayName("累加收尾信息：空状态沿用原状态，超长错误信息被截断")
    void shouldAccumulateKeepStatusAndAbbreviateError() {
        AiTrace exists = new AiTrace();
        exists.setStatus(AiTrace.STATUS_RUNNING);
        exists.setDurationMs(null);
        exists.setToolCallCount(null);
        exists.setPromptTokens(null);
        exists.setCompletionTokens(null);
        exists.setTotalTokens(null);
        when(baseMapper.selectById(1L)).thenReturn(exists);

        service.accumulate(1L, "  ", null, null, null, null, null, "e".repeat(1500));

        ArgumentCaptor<AiTrace> captor = ArgumentCaptor.forClass(AiTrace.class);
        verify(baseMapper).updateById(captor.capture());
        AiTrace update = captor.getValue();
        assertEquals(AiTrace.STATUS_RUNNING, update.getStatus());
        assertNull(update.getDurationMs());
        assertEquals(1000, update.getErrorMsg().length());
    }

    @Test
    @DisplayName("累加收尾信息：更新异常被吞掉")
    void shouldSwallowAccumulateError() {
        AiTrace exists = new AiTrace();
        exists.setStatus(AiTrace.STATUS_RUNNING);
        when(baseMapper.selectById(1L)).thenReturn(exists);
        when(baseMapper.updateById(any(AiTrace.class))).thenThrow(new RuntimeException("db"));

        assertDoesNotThrow(() -> service.accumulate(1L, "success", 1L, 1, 1, 1, 1, null));
    }

}