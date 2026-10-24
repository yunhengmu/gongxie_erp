package org.dromara.ai.job;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.service.IAiAgentTaskService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时 Agent 任务派发调度器（应用内）。
 *
 * <p>每 60 秒扫描 {@code ai_agent_task} 中到点的启用任务并提交执行；任务定义、Cron 与执行记录
 * 全部由本平台维护，无需外部调度中心。多实例部署时由 {@code last_run_time} 的原子抢占去重，
 * 不需要分布式调度。</p>
 *
 * <p>与长期记忆维护、中断任务回收一致，使用单线程守护调度器 + 应用就绪后启动，
 * 避免开启全局 {@code @EnableScheduling}。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAgentTaskDispatchScheduler {

    /**
     * 派发周期（秒）：与任务 Cron 的最小粒度 1 分钟对齐
     */
    private static final int DISPATCH_INTERVAL_SECONDS = 60;

    /**
     * 启动后首轮延迟（秒）：等连接池与会话初始化完成
     */
    private static final int INITIAL_DELAY_SECONDS = 60;

    private final IAiAgentTaskService aiAgentTaskService;

    /**
     * 周期调度器（单线程守护，避免与业务线程争抢）
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-task-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 应用就绪后启动周期派发。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::dispatchQuietly, INITIAL_DELAY_SECONDS,
            DISPATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("定时 Agent 任务调度器已启动：intervalSeconds={}, initialDelaySeconds={}",
            DISPATCH_INTERVAL_SECONDS, INITIAL_DELAY_SECONDS);
    }

    /**
     * 应用关闭时停止调度。
     */
    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 执行一轮派发（幂等，可重复调用）。
     *
     * @return 本次触发的任务数
     */
    public int tick() {
        int triggered = aiAgentTaskService.dispatchDueTasks();
        if (triggered > 0) {
            log.info("定时 Agent 任务派发完成，本次触发 {} 个任务", triggered);
        }
        return triggered;
    }

    /**
     * 执行一轮派发：异常只记录，不影响下一轮。
     */
    void dispatchQuietly() {
        try {
            tick();
        } catch (Exception e) {
            log.error("定时 Agent 任务派发失败，下一轮继续", e);
        }
    }

}
