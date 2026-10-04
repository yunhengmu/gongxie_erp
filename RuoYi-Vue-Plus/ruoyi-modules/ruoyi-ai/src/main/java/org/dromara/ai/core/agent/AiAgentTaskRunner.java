package org.dromara.ai.core.agent;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 无人值守执行线程池（定时任务与渠道接入共用）。
 *
 * <p>派发（SnailJob 每 60 秒触发）、「立即执行」与渠道入站回调都只负责提交，不阻塞：
 * 定时任务的执行状态由 {@code ai_agent_task_run} 跟踪，页面按该记录查看结果；
 * 渠道入站的结果落在 {@code ai_session} / {@code ai_message}，可在会话管理页检索。</p>
 *
 * @author BizBuddy
 */
@Component
public class AiAgentTaskRunner {

    /**
     * 执行线程池（有界，避免任务堆积拖垮应用）
     */
    private final ThreadPoolTaskExecutor executor;

    public AiAgentTaskRunner() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(100);
        pool.setThreadNamePrefix("ai-agent-task-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
    }

    /**
     * 提交一次任务执行。
     *
     * @param runnable 执行体
     */
    public void submit(Runnable runnable) {
        executor.execute(runnable);
    }

    /**
     * 应用关闭时优雅等待在跑任务结束。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

}
