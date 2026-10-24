package org.dromara.ai.core.document;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 知识库文档批量处理线程池。
 *
 * <p>解析与向量化都可能持续数十秒，批量执行绝不能占用 HTTP 请求线程：
 * 提交接口只做校验与状态占位后立即返回，实际处理在此线程池内异步完成，
 * 进度由 {@code ai_document.parse_status / vector_status} 承载，页面轮询读取。</p>
 *
 * <p>并发刻意压到 2：解析要下载并读文件，向量化要外呼 embedding 并写 Milvus，
 * 并发过高会与主业务争抢数据库连接池，反而拖慢整站。</p>
 *
 * @author BizBuddy
 */
@Component
public class AiDocumentBatchRunner {

    /**
     * 执行线程池（有界，队列满时拒绝而不是无限堆积）
     */
    private final ThreadPoolTaskExecutor executor;

    public AiDocumentBatchRunner() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(200);
        pool.setThreadNamePrefix("ai-kb-batch-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
    }

    /**
     * 提交一个批量处理任务。
     *
     * @param task 执行体
     * @throws org.springframework.core.task.TaskRejectedException 队列已满时抛出
     */
    public void submit(Runnable task) {
        executor.execute(task);
    }

    /**
     * 应用关闭时优雅等待在跑任务结束。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

}
