package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiAgentTaskRunner;
import org.dromara.ai.core.agent.AiChatFacade;
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
import org.dromara.ai.service.IAiAgentTaskService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 定时任务 Service 业务层处理
 *
 * <p>调度由应用内调度器承载（每 60 秒），调用 {@link #dispatchDueTasks()} 后由本服务
 * 按 Cron 判定到点任务并提交执行。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiAgentTaskServiceImpl implements IAiAgentTaskService {

    /**
     * 无人值守会话标题的时间后缀格式
     */
    private static final DateTimeFormatter TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AiAgentTaskMapper baseMapper;

    private final AiAgentTaskRunMapper runMapper;

    private final IAiAgentService agentService;

    private final IAiSessionService sessionService;

    private final AiChatFacade chatFacade;

    private final AiAgentTaskRunner taskRunner;

    private final UserService userService;

    private final DeptService deptService;

    @Override
    public PageResult<AiAgentTaskVo> queryPageList(AiAgentTaskBo bo, PageQuery pageQuery) {
        Page<AiAgentTaskVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        fillDisplayFields(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AiAgentTaskVo queryById(Long id) {
        AiAgentTaskVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("定时任务不存在或已被删除");
        }
        fillDisplayFields(List.of(vo));
        return vo;
    }

    @Override
    public Boolean insertByBo(AiAgentTaskBo bo) {
        AiAgentTask add = MapstructUtils.convert(bo, AiAgentTask.class);
        applyValidated(add);
        validTaskNameUnique(add);
        // last_run_time 保持为空：首次到点判定以创建时间为基准，避免新建即补跑历史场次
        add.setLastRunTime(null);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(AiAgentTaskBo bo) {
        AiAgentTask exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("定时任务不存在或已被删除");
        }
        AiAgentTask update = MapstructUtils.convert(bo, AiAgentTask.class);
        applyValidated(update);
        validTaskNameUnique(update);
        // 排期基准沿用原值：改 Cron 不会触发补跑，也不影响原有到点判定
        update.setLastRunTime(exists.getLastRunTime());
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的定时任务");
        }
        if (baseMapper.selectByIds(ids).size() != ids.size()) {
            throw new ServiceException("定时任务不存在或已被删除");
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public Long runNow(Long taskId) {
        AiAgentTask task = requireTask(taskId);
        return startRun(task, AiAgentTaskRun.TRIGGER_MANUAL);
    }

    @Override
    public PageResult<AiAgentTaskRunVo> queryRunPageList(Long taskId, PageQuery pageQuery) {
        LambdaQueryWrapper<AiAgentTaskRun> lqw = Wrappers.lambdaQuery();
        lqw.eq(taskId != null, AiAgentTaskRun::getTaskId, taskId);
        lqw.orderByDesc(AiAgentTaskRun::getId);
        Page<AiAgentTaskRunVo> result = runMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public int dispatchDueTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<AiAgentTask> tasks = baseMapper.selectList(Wrappers.<AiAgentTask>lambdaQuery()
            .eq(AiAgentTask::getStatus, AiAgentTask.STATUS_ENABLED));
        int triggered = 0;
        for (AiAgentTask task : tasks) {
            LocalDateTime scheduled = resolveDueTime(task, now);
            if (scheduled == null || !claim(task.getId(), scheduled)) {
                continue;
            }
            startRun(task, AiAgentTaskRun.TRIGGER_CRON);
            triggered++;
        }
        return triggered;
    }

    /**
     * 判定任务是否到点，返回本次应执行的计划执行时间。
     *
     * <p>基准取「上次计划执行时间」（为空时取创建时间），其后第一个触发时刻不晚于当前时间即视为到点。</p>
     *
     * @param task 任务实体
     * @param now  当前时间
     * @return 本次计划执行时间；未到点或 Cron 非法时返回 null
     */
    private LocalDateTime resolveDueTime(AiAgentTask task, LocalDateTime now) {
        LocalDateTime baseline = task.getLastRunTime() != null ? task.getLastRunTime() : task.getCreateTime();
        if (baseline == null) {
            return null;
        }
        CronExpression cron;
        try {
            cron = parseCron(task.getCronExpression());
        } catch (ServiceException e) {
            log.warn("定时任务 Cron 非法，跳过派发：taskId={}, cron={}", task.getId(), task.getCronExpression());
            return null;
        }
        LocalDateTime next = cron.next(baseline);
        return next != null && !next.isAfter(now) ? next : null;
    }

    /**
     * 以「计划执行时间」原子抢占本次执行权（并发去重）。
     *
     * @param taskId    任务主键
     * @param scheduled 本次计划执行时间
     * @return 是否抢到执行权
     */
    private boolean claim(Long taskId, LocalDateTime scheduled) {
        AiAgentTask update = new AiAgentTask();
        update.setLastRunTime(scheduled);
        LambdaUpdateWrapper<AiAgentTask> luw = Wrappers.lambdaUpdate();
        luw.eq(AiAgentTask::getId, taskId);
        luw.and(w -> w.isNull(AiAgentTask::getLastRunTime).or().lt(AiAgentTask::getLastRunTime, scheduled));
        return baseMapper.update(update, luw) > 0;
    }

    /**
     * 登记执行记录并提交异步执行。
     *
     * @param task        任务实体
     * @param triggerType 触发方式
     * @return 执行记录主键
     */
    private Long startRun(AiAgentTask task, String triggerType) {
        AiAgentTaskRun run = new AiAgentTaskRun();
        run.setTaskId(task.getId());
        run.setTaskName(task.getTaskName());
        run.setAgentId(task.getAgentId());
        run.setTriggerType(triggerType);
        run.setStatus(AiAgentTaskRun.STATUS_RUNNING);
        run.setStartTime(LocalDateTime.now());
        run.setToolCallCount(0);
        runMapper.insert(run);
        // 异步执行：派发不阻塞调度线程，「立即执行」也不占用 HTTP 请求；结果由执行记录跟踪
        taskRunner.submit(() -> executeRun(task, run.getId()));
        return run.getId();
    }

    /**
     * 执行一次任务：以运行身份用户落库会话与消息，产出的结果即「后台任务结果」。
     *
     * @param task  任务实体
     * @param runId 执行记录主键
     */
    private void executeRun(AiAgentTask task, Long runId) {
        Long sessionId = null;
        AiTaskExecution execution;
        try {
            // 会话创建会校验智能体是否启用；无人值守链路不做会话归属校验
            String title = task.getTaskName() + " · " + LocalDateTime.now().format(TITLE_TIME_FORMAT);
            AiSession session = sessionService.createUnattendedSession(task.getAgentId(), title,
                task.getRunAsUserId(), task.getRunAsDeptId(), AiSession.SOURCE_TASK);
            sessionId = session.getId();
            AiMessage userMessage = sessionService.appendUserMessageUnattended(sessionId, task.getTaskPrompt());
            execution = chatFacade.executeUnattended(session, userMessage, task.getRunAsUserId());
        } catch (Exception e) {
            log.error("定时任务执行异常，taskId={}, runId={}", task.getId(), runId, e);
            finishRun(runId, null, null, AiAgentTaskRun.STATUS_FAILED, e.getMessage(), null);
            return;
        }
        finishRun(runId, execution.sessionId(), execution.messageId(),
            execution.success() ? AiAgentTaskRun.STATUS_SUCCESS : AiAgentTaskRun.STATUS_FAILED,
            execution.errorMsg(), execution);
    }

    /**
     * 回写执行记录。
     *
     * @param runId      执行记录主键
     * @param sessionId  本次执行产生的会话
     * @param messageId  本次执行的助手消息
     * @param status     执行状态
     * @param errorMsg   失败原因
     * @param execution  执行结果（可为空）
     */
    private void finishRun(Long runId, Long sessionId, Long messageId, String status,
                           String errorMsg, AiTaskExecution execution) {
        AiAgentTaskRun update = new AiAgentTaskRun();
        update.setId(runId);
        update.setSessionId(sessionId);
        update.setMessageId(messageId);
        update.setStatus(status);
        update.setEndTime(LocalDateTime.now());
        update.setErrorMsg(StringUtils.substring(errorMsg, 0, AiAgentTaskRun.ERROR_MSG_MAX_LENGTH));
        if (execution != null) {
            update.setDurationMs(execution.durationMs());
            update.setPromptTokens(execution.promptTokens());
            update.setCompletionTokens(execution.completionTokens());
            update.setTotalTokens(execution.totalTokens());
            update.setToolCallCount(execution.toolCallCount());
        }
        try {
            runMapper.updateById(update);
        } catch (Exception e) {
            log.error("回写定时任务执行记录失败，runId={}", runId, e);
        }
    }

    /**
     * 取值并校验：Cron 合法性、智能体可用性、运行身份用户与部门。
     *
     * @param entity 待保存实体
     */
    private void applyValidated(AiAgentTask entity) {
        entity.setTaskName(StringUtils.trim(entity.getTaskName()));
        entity.setCronExpression(StringUtils.trim(entity.getCronExpression()));
        parseCron(entity.getCronExpression());
        // 智能体必须存在且启用（与对话口径一致），并快照编码便于追溯
        AiAgent agent = agentService.getEnabledAgent(entity.getAgentId());
        entity.setAgentCode(agent.getAgentCode());
        // 运行身份用户必须存在；部门随用户带出，作为该会话的数据权限归属
        UserDTO runAsUser = userService.selectById(entity.getRunAsUserId());
        if (runAsUser == null) {
            throw new ServiceException("运行身份用户不存在");
        }
        entity.setRunAsDeptId(runAsUser.getDeptId());
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(AiAgentTask.STATUS_ENABLED);
        }
    }

    /**
     * 校验任务名称唯一。
     *
     * @param entity 待保存实体
     */
    private void validTaskNameUnique(AiAgentTask entity) {
        LambdaQueryWrapper<AiAgentTask> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentTask::getTaskName, entity.getTaskName());
        lqw.ne(entity.getId() != null, AiAgentTask::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("任务名称已存在：" + entity.getTaskName());
        }
    }

    /**
     * 解析 Cron 表达式（Spring 6 位：秒 分 时 日 月 周）。
     *
     * @param expression Cron 表达式
     * @return 解析后的表达式
     */
    private CronExpression parseCron(String expression) {
        String expr = StringUtils.trim(expression);
        if (StringUtils.isBlank(expr)) {
            throw new ServiceException("Cron 表达式不能为空");
        }
        try {
            return CronExpression.parse(expr);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("Cron 表达式不合法（需 6 位：秒 分 时 日 月 周，如 0 0 9 * * MON-FRI）：" + expr);
        }
    }

    /**
     * 查询任务实体（不存在时抛业务异常）。
     *
     * @param taskId 任务主键
     * @return 任务实体
     */
    private AiAgentTask requireTask(Long taskId) {
        AiAgentTask task = taskId == null ? null : baseMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("定时任务不存在或已被删除");
        }
        return task;
    }

    /**
     * 填充智能体名称 / 运行身份用户与部门名称，并按 Cron 计算预计下次执行时间。
     *
     * @param list 任务列表
     */
    private void fillDisplayFields(List<AiAgentTaskVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> agentIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> deptIds = new HashSet<>();
        for (AiAgentTaskVo vo : list) {
            if (vo.getAgentId() != null) {
                agentIds.add(vo.getAgentId());
            }
            if (vo.getRunAsUserId() != null) {
                userIds.add(vo.getRunAsUserId());
            }
            if (vo.getRunAsDeptId() != null) {
                deptIds.add(vo.getRunAsDeptId());
            }
        }
        Map<Long, AiAgent> agents = new HashMap<>();
        if (!agentIds.isEmpty()) {
            agentService.queryEntities(agentIds).forEach(agent -> agents.put(agent.getId(), agent));
        }
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptService.selectDeptNamesByIds(deptIds);
        LocalDateTime now = LocalDateTime.now();
        for (AiAgentTaskVo vo : list) {
            AiAgent agent = agents.get(vo.getAgentId());
            if (agent != null) {
                vo.setAgentName(agent.getAgentName());
                vo.setAgentStatus(agent.getStatus());
            }
            vo.setRunAsUserName(vo.getRunAsUserId() == null ? null : userNames.get(vo.getRunAsUserId()));
            vo.setRunAsDeptName(vo.getRunAsDeptId() == null ? null : deptNames.get(vo.getRunAsDeptId()));
            vo.setNextRunTime(resolveNextRunTime(vo, now));
        }
    }

    /**
     * 计算预计下次执行时间（仅启用中的任务展示，Cron 非法时为空）。
     *
     * @param vo  任务视图对象
     * @param now 当前时间
     * @return 预计下次执行时间
     */
    private LocalDateTime resolveNextRunTime(AiAgentTaskVo vo, LocalDateTime now) {
        if (!AiAgentTask.STATUS_ENABLED.equals(vo.getStatus())) {
            return null;
        }
        try {
            return parseCron(vo.getCronExpression()).next(now);
        } catch (ServiceException e) {
            return null;
        }
    }

    /**
     * 构建任务动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiAgentTask> buildQueryWrapper(AiAgentTaskBo bo) {
        LambdaQueryWrapper<AiAgentTask> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getKeyword()), AiAgentTask::getTaskName, bo.getKeyword());
        lqw.eq(bo.getAgentId() != null, AiAgentTask::getAgentId, bo.getAgentId());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiAgentTask::getStatus, bo.getStatus());
        lqw.orderByDesc(AiAgentTask::getCreateTime);
        return lqw;
    }

}
