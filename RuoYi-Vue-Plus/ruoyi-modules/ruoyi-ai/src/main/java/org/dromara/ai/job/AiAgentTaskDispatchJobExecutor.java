package org.dromara.ai.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.service.IAiAgentTaskService;
import org.springframework.stereotype.Component;

/**
 * 定时 Agent 任务派发执行器（SnailJob）。
 *
 * <p>SnailJob 侧只需登记「一个」周期任务（建议每 60 秒）指向本执行器：由本执行器扫描
 * {@code ai_agent_task} 中到点的启用任务并逐个提交执行，因此新增 / 修改任务无需到
 * SnailJob 控制台逐条建任务（见决策 D7 与 A07 §9）。</p>
 *
 * <p>派发只做「到点判定 + 原子抢占 + 提交」，执行本身在应用线程池中异步完成，
 * 单次执行状态记录在 {@code ai_agent_task_run} 中。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
@JobExecutor(name = "aiAgentTaskDispatchExecutor")
public class AiAgentTaskDispatchJobExecutor {

    private final IAiAgentTaskService aiAgentTaskService;

    /**
     * 执行派发：扫描到点任务并提交执行。
     *
     * @param jobArgs 任务参数（未使用）
     * @return 执行结果
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        int triggered = aiAgentTaskService.dispatchDueTasks();
        log.info("定时 Agent 任务派发完成，本次触发 {} 个任务", triggered);
        return ExecuteResult.success("本次触发 " + triggered + " 个任务");
    }

}
