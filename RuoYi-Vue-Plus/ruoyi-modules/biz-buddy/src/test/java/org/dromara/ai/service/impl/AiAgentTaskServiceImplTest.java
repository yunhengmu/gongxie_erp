package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.core.agent.AiAgentTaskRunner;
import org.dromara.ai.core.agent.AiChatFacade;
import org.dromara.ai.core.task.TaskOutputDispatcher;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentTask;
import org.dromara.ai.domain.AiAgentTaskRun;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiAgentTaskBo;
import org.dromara.ai.domain.dto.AiTaskExecution;
import org.dromara.ai.domain.vo.AiAgentTaskRunVo;
import org.dromara.ai.domain.vo.AiAgentTaskVo;
import org.dromara.ai.mapper.AiAgentTaskMapper;
import org.dromara.ai.mapper.AiAgentTaskRunMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link AiAgentTaskServiceImpl} 单元测试：覆盖 CRUD 校验、到点派发抢占与执行记录回写。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 定时任务 Service 单元测试")
class AiAgentTaskServiceImplTest {

    @Mock
    private AiAgentTaskMapper baseMapper;

    @Mock
    private AiAgentTaskRunMapper runMapper;

    @Mock
    private IAiAgentService agentService;

    @Mock
    private IAiSessionService sessionService;

    @Mock
    private AiChatFacade chatFacade;

    @Mock
    private AiAgentTaskRunner taskRunner;

    @Mock
    private TaskOutputDispatcher outputDispatcher;

    @Mock
    private UserService userService;

    @Mock
    private DeptService deptService;

    @InjectMocks
    private AiAgentTaskServiceImpl taskService;

    /**
     * 复用共享的最小 Spring 容器，供 MapstructUtils 静态初始化使用。
     */
    @BeforeAll
    static void initStaticContext() {
        AiServiceTestContext.initialize();
    }

    /**
     * 验证按主键查询不存在时抛业务异常。
     */
    @Test
    @DisplayName("查询不存在的主键抛异常")
    void shouldThrowWhenQueryByIdMissing() {
        when(baseMapper.selectVoById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> taskService.queryById(1L));
    }

    /**
     * 验证查询详情补齐智能体 / 用户 / 部门名称并计算下次执行时间。
     */
    @Test
    @DisplayName("查询详情补齐展示字段")
    void shouldFillDisplayFields() {
        AiAgentTaskVo vo = new AiAgentTaskVo();
        vo.setId(1L);
        vo.setAgentId(5L);
        vo.setRunAsUserId(7L);
        vo.setRunAsDeptId(3L);
        vo.setStatus(AiAgentTask.STATUS_ENABLED);
        vo.setCronExpression("* * * * * *");
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        AiAgent agent = new AiAgent() {
        };
        agent.setId(5L);
        agent.setAgentName("小Z");
        agent.setStatus("0");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(7L, "张三"));
        when(deptService.selectDeptNamesByIds(any())).thenReturn(Map.of(3L, "研发部"));

        AiAgentTaskVo result = taskService.queryById(1L);

        assertEquals("小Z", result.getAgentName());
        assertEquals("张三", result.getRunAsUserName());
        assertEquals("研发部", result.getRunAsDeptName());
        assertNotNull(result.getNextRunTime());
    }

    /**
     * 验证分页查询返回列表。
     */
    @Test
    @DisplayName("分页查询返回列表")
    void shouldQueryPageList() {
        AiAgentTaskVo vo = new AiAgentTaskVo();
        vo.setId(1L);
        vo.setStatus("1");
        Page<AiAgentTaskVo> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(vo));
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiAgentTaskVo> result = taskService.queryPageList(new AiAgentTaskBo(), new PageQuery());

        assertEquals(1, result.getRows().size());
        assertNull(vo.getNextRunTime());
    }

    /**
     * 验证新增成功写入智能体编码快照、运行身份部门与默认状态。
     */
    @Test
    @DisplayName("新增成功")
    void shouldInsertSuccessfully() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        AiAgentTask add = new AiAgentTask();
        add.setTaskName(" 每日 ");
        add.setAgentId(5L);
        add.setCronExpression("* * * * * *");
        add.setRunAsUserId(7L);
        add.setLastRunTime(LocalDateTime.now());
        when(agentService.getEnabledAgent(5L)).thenReturn(agent(5L, "z"));
        when(userService.selectById(7L)).thenReturn(user(7L, 3L));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(AiAgentTask.class))).thenAnswer(inv -> {
            ((AiAgentTask) inv.getArgument(0)).setId(100L);
            return 1;
        });

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiAgentTask.class)).thenReturn(add);
            assertTrue(taskService.insertByBo(bo));
        }

        assertEquals(100L, bo.getId());
        assertEquals("每日", add.getTaskName());
        assertEquals("z", add.getAgentCode());
        assertEquals(3L, add.getRunAsDeptId());
        assertEquals(AiAgentTask.STATUS_ENABLED, add.getStatus());
        assertNull(add.getLastRunTime());
    }

    /**
     * 验证 Cron 非法时抛业务异常。
     */
    @Test
    @DisplayName("Cron 非法时抛异常")
    void shouldRejectInvalidCron() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        AiAgentTask add = new AiAgentTask();
        add.setTaskName("t");
        add.setCronExpression("bad");

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiAgentTask.class)).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class, () -> taskService.insertByBo(bo));
            assertTrue(ex.getMessage().contains("Cron"));
        }
    }

    /**
     * 验证运行身份用户不存在时抛业务异常。
     */
    @Test
    @DisplayName("运行身份用户不存在抛异常")
    void shouldRejectMissingRunAsUser() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        AiAgentTask add = new AiAgentTask();
        add.setTaskName("t");
        add.setCronExpression("* * * * * *");
        add.setAgentId(5L);
        add.setRunAsUserId(7L);
        when(agentService.getEnabledAgent(5L)).thenReturn(agent(5L, "z"));
        when(userService.selectById(7L)).thenReturn(null);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiAgentTask.class)).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class, () -> taskService.insertByBo(bo));
            assertTrue(ex.getMessage().contains("运行身份用户"));
        }
    }

    /**
     * 验证任务名称重复时抛业务异常。
     */
    @Test
    @DisplayName("任务名称重复抛异常")
    void shouldRejectDuplicateName() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        AiAgentTask add = new AiAgentTask();
        add.setTaskName("t");
        add.setCronExpression("* * * * * *");
        add.setAgentId(5L);
        add.setRunAsUserId(7L);
        when(agentService.getEnabledAgent(5L)).thenReturn(agent(5L, "z"));
        when(userService.selectById(7L)).thenReturn(user(7L, 3L));
        when(baseMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiAgentTask.class)).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class, () -> taskService.insertByBo(bo));
            assertTrue(ex.getMessage().contains("任务名称已存在"));
        }
    }

    /**
     * 验证修改目标不存在时抛业务异常。
     */
    @Test
    @DisplayName("修改目标不存在抛异常")
    void shouldThrowWhenUpdateMissing() {
        AiAgentTaskBo bo = new AiAgentTaskBo();
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> taskService.updateByBo(bo));
    }

    /**
     * 验证修改成功沿用原排期基准。
     */
    @Test
    @DisplayName("修改成功沿用排期基准")
    void shouldUpdateSuccessfully() {
        LocalDateTime lastRun = LocalDateTime.of(2026, 1, 1, 9, 0);
        AiAgentTaskBo bo = new AiAgentTaskBo();
        bo.setId(1L);
        AiAgentTask exists = new AiAgentTask();
        exists.setLastRunTime(lastRun);
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiAgentTask update = new AiAgentTask();
        when(agentService.getEnabledAgent(any())).thenReturn(agent(5L, "z"));
        when(userService.selectById(any())).thenReturn(user(7L, 3L));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.updateById(any(AiAgentTask.class))).thenReturn(1);
        update.setTaskName("t");
        update.setCronExpression("* * * * * *");
        update.setAgentId(5L);
        update.setRunAsUserId(7L);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiAgentTask.class)).thenReturn(update);
            assertTrue(taskService.updateByBo(bo));
        }
        assertEquals(lastRun, update.getLastRunTime());
    }

    /**
     * 验证删除时未选择主键与主键缺失分别抛业务异常。
     */
    @Test
    @DisplayName("删除校验主键")
    void shouldValidateDeleteIds() {
        assertThrows(ServiceException.class, () -> taskService.deleteWithValidByIds(List.of()));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiAgentTask()));
        assertThrows(ServiceException.class, () -> taskService.deleteWithValidByIds(List.of(1L, 2L)));
    }

    /**
     * 验证删除成功。
     */
    @Test
    @DisplayName("删除成功")
    void shouldDeleteSuccessfully() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiAgentTask()));
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(taskService.deleteWithValidByIds(List.of(1L)));
    }

    /**
     * 验证立即执行登记执行记录并同步执行，成功回写状态。
     */
    @Test
    @DisplayName("立即执行成功回写")
    void shouldRunNowSuccessfully() {
        AiAgentTask task = task(1L, "* * * * * *");
        when(baseMapper.selectById(1L)).thenReturn(task);
        stubRunInsert(99L);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(taskRunner).submit(any(Runnable.class));
        AiSession session = mock(AiSession.class);
        when(session.getId()).thenReturn(500L);
        when(sessionService.createUnattendedSession(eq(5L), anyString(), eq(7L), eq(3L), eq(AiSession.SOURCE_TASK)))
            .thenReturn(session);
        AiMessage message = mock(AiMessage.class);
        when(sessionService.appendUserMessageUnattended(500L, "p")).thenReturn(message);
        when(chatFacade.executeUnattended(session, message, 7L)).thenReturn(
            new AiTaskExecution(500L, 600L, true, null, 120L, 2, 10, 20, 30));

        Long runId = taskService.runNow(1L);

        assertEquals(99L, runId);
        ArgumentCaptor<AiAgentTaskRun> insertCaptor = ArgumentCaptor.forClass(AiAgentTaskRun.class);
        verify(runMapper).insert(insertCaptor.capture());
        assertEquals(AiAgentTaskRun.TRIGGER_MANUAL, insertCaptor.getValue().getTriggerType());
        ArgumentCaptor<AiAgentTaskRun> updateCaptor = ArgumentCaptor.forClass(AiAgentTaskRun.class);
        verify(runMapper).updateById(updateCaptor.capture());
        AiAgentTaskRun update = updateCaptor.getValue();
        assertEquals(AiAgentTaskRun.STATUS_SUCCESS, update.getStatus());
        assertEquals(500L, update.getSessionId());
        assertEquals(600L, update.getMessageId());
        assertEquals(30, update.getTotalTokens());
    }

    /**
     * 验证执行异常时回写失败状态与原因。
     */
    @Test
    @DisplayName("执行异常回写失败")
    void shouldRecordFailureWhenExecuteThrows() {
        AiAgentTask task = task(1L, "* * * * * *");
        when(baseMapper.selectById(1L)).thenReturn(task);
        stubRunInsert(99L);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(taskRunner).submit(any(Runnable.class));
        when(sessionService.createUnattendedSession(any(), anyString(), any(), any(), anyString()))
            .thenThrow(new RuntimeException("boom"));

        taskService.runNow(1L);

        ArgumentCaptor<AiAgentTaskRun> captor = ArgumentCaptor.forClass(AiAgentTaskRun.class);
        verify(runMapper).updateById(captor.capture());
        assertEquals(AiAgentTaskRun.STATUS_FAILED, captor.getValue().getStatus());
        assertEquals("boom", captor.getValue().getErrorMsg());
    }

    /**
     * 验证任务不存在时立即执行抛业务异常。
     */
    @Test
    @DisplayName("任务不存在时立即执行抛异常")
    void shouldThrowWhenRunNowMissing() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> taskService.runNow(1L));
    }

    /**
     * 验证派发仅触发到点任务，跳过未到点与 Cron 非法任务。
     */
    @Test
    @DisplayName("派发仅触达到点任务")
    void shouldDispatchOnlyDueTasks() {
        AiAgentTask due = task(1L, "* * * * * *");
        due.setCreateTime(LocalDateTime.now().minusHours(1));
        AiAgentTask notDue = task(2L, "* * * * * *");
        notDue.setCreateTime(LocalDateTime.now().plusDays(1));
        AiAgentTask invalid = task(3L, "bad");
        invalid.setCreateTime(LocalDateTime.now().minusHours(1));
        when(baseMapper.selectList(any())).thenReturn(List.of(due, notDue, invalid));
        when(baseMapper.update(any(), any())).thenReturn(1);

        assertEquals(1, taskService.dispatchDueTasks());
        verify(baseMapper, times(1)).update(any(), any());
        verify(runMapper, times(1)).insert(any(AiAgentTaskRun.class));
    }

    /**
     * 验证抢占失败的任务不触发执行。
     */
    @Test
    @DisplayName("抢占失败不触发执行")
    void shouldSkipWhenClaimFails() {
        AiAgentTask due = task(1L, "* * * * * *");
        due.setLastRunTime(LocalDateTime.now().minusHours(2));
        when(baseMapper.selectList(any())).thenReturn(List.of(due));
        when(baseMapper.update(any(), any())).thenReturn(0);

        assertEquals(0, taskService.dispatchDueTasks());
        verify(runMapper, never()).insert(any(AiAgentTaskRun.class));
    }

    /**
     * 验证执行记录分页查询。
     */
    @Test
    @DisplayName("分页查询执行记录")
    void shouldQueryRunPageList() {
        Page<AiAgentTaskRunVo> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(new AiAgentTaskRunVo()));
        when(runMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiAgentTaskRunVo> result = taskService.queryRunPageList(1L, new PageQuery());

        assertEquals(1, result.getRows().size());
    }

    private void stubRunInsert(Long id) {
        when(runMapper.insert(any(AiAgentTaskRun.class))).thenAnswer(inv -> {
            ((AiAgentTaskRun) inv.getArgument(0)).setId(id);
            return 1;
        });
    }

    private AiAgentTask task(Long id, String cron) {
        AiAgentTask task = new AiAgentTask();
        task.setId(id);
        task.setTaskName("t" + id);
        task.setAgentId(5L);
        task.setTaskPrompt("p");
        task.setRunAsUserId(7L);
        task.setRunAsDeptId(3L);
        task.setCronExpression(cron);
        return task;
    }

    private AiAgent agent(Long id, String code) {
        AiAgent agent = new AiAgent() {
        };
        agent.setId(id);
        agent.setAgentCode(code);
        return agent;
    }

    private UserDTO user(Long id, Long deptId) {
        UserDTO user = new UserDTO();
        user.setUserId(id);
        user.setDeptId(deptId);
        return user;
    }

}