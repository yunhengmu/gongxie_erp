package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiAgentTaskBo;
import org.dromara.ai.domain.vo.AiAgentTaskRunVo;
import org.dromara.ai.domain.vo.AiAgentTaskVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiAgentTaskService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiAgentTaskController} 单元测试：定时任务 CRUD、执行记录与手动触发。
 */
@DisplayName("AI 定时任务 Controller")
class AiAgentTaskControllerTest {

    private final IAiAgentTaskService taskService = mock(IAiAgentTaskService.class);

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final AiAgentTaskController controller = new AiAgentTaskController(taskService, agentService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询任务列表")
    void shouldList() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        PageQuery query = new PageQuery();
        PageResult<AiAgentTaskVo> page = PageResult.build();
        when(taskService.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询智能体选项")
    void shouldAgentOptions() {
        List<AiAgentVo> options = List.of(new AiAgentVo());
        when(agentService.queryOptions()).thenReturn(options);
        assertSame(options, controller.agentOptions().getData());
    }

    @Test
    @DisplayName("获取任务详情与执行记录")
    void shouldGetInfoAndRuns() {
        AiAgentTaskVo vo = new AiAgentTaskVo();
        PageQuery query = new PageQuery();
        PageResult<AiAgentTaskRunVo> runs = PageResult.build();
        when(taskService.queryById(2L)).thenReturn(vo);
        when(taskService.queryRunPageList(2L, query)).thenReturn(runs);
        assertSame(vo, controller.getInfo(2L).getData());
        assertSame(runs, controller.runs(2L, query).getData());
    }

    @Test
    @DisplayName("新增任务成功")
    void shouldAdd() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        when(taskService.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改任务失败返回失败码")
    void shouldEditFail() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        when(taskService.updateByBo(bo)).thenReturn(false);
        assertFalse(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("批量删除任务")
    void shouldRemove() {
        when(taskService.deleteWithValidByIds(List.of(1L, 2L))).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{1L, 2L})));
    }

    @Test
    @DisplayName("立即执行任务返回运行记录主键")
    void shouldRun() {
        when(taskService.runNow(2L)).thenReturn(88L);
        assertEquals(88L, controller.run(2L).getData());
    }

}