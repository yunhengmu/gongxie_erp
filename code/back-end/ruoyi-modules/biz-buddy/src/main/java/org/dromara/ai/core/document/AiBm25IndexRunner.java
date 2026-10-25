package org.dromara.ai.core.document;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.service.IAiVectorService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库 BM25 索引重建线程池。
 *
 * <p>切换为混合检索或手动重建时，按全部已向量化文档逐片写入 BM25 索引，耗时随文档量增长，
 * 因此不占用 HTTP 请求线程；构建状态由 {@code ai_knowledge_base.bm25_index_status} 承载。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiBm25IndexRunner {

    /**
     * 执行线程池（单线程串行：同一知识库避免并发重建互相踩踏）
     */
    private final ThreadPoolTaskExecutor executor;

    private final IAiVectorService vectorService;

    /**
     * 正在重建的知识库（重复提交直接跳过）
     */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public AiBm25IndexRunner(IAiVectorService vectorService) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(20);
        pool.setThreadNamePrefix("ai-kb-bm25-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
        this.vectorService = vectorService;
    }

    /**
     * 异步重建指定知识库的 BM25 索引（已在重建中则忽略）。
     *
     * @param kbId 知识库主键
     */
    public void rebuild(Long kbId) {
        if (kbId == null || !running.add(kbId)) {
            return;
        }
        executor.execute(() -> {
            try {
                vectorService.rebuildBm25Index(kbId);
            } catch (Exception e) {
                log.error("BM25 索引异步重建失败，kbId={}", kbId, e);
            } finally {
                running.remove(kbId);
            }
        });
    }

    /**
     * 应用关闭时优雅等待在跑任务结束。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
