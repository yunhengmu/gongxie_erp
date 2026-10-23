package org.dromara.ai.core.agent;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.memory.AiMemoryExtractionService;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 平台托管记忆管道（A2 / T16）：轮末异步执行「抽取 → 自动合并」。
 *
 * <p>只有在 {@code memory-pipeline-mode=platform} 时生效；framework / shadow 模式为空操作。</p>
 *
 * <p>节流与并发：</p>
 * <ul>
 *   <li>抽取 gate 由 {@link AiMemoryExtractionService} 按用户节流；</li>
 *   <li>合并 gate 按用户节流（复用 {@code consolidationMinGapMinutes}）；</li>
 *   <li>合并执行再加一把按用户 Redis 锁，避免自动合并与人工合并并发覆盖。</li>
 * </ul>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiMemoryPipeline {

    /**
     * 自动合并 gate 前缀
     */
    private static final String CONSOLIDATE_GATE_PREFIX = "bizbuddy:ai:memory:consolidate:gate:";

    /**
     * 自动合并执行锁前缀
     */
    private static final String CONSOLIDATE_LOCK_PREFIX = "bizbuddy:ai:memory:consolidate:lock:";

    /**
     * 执行锁 TTL：覆盖一次最长合并（20 批 × 5 分钟），异常退出后自动释放
     */
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    private final AiMemoryExtractionService extractionService;

    private final IAiMemoryService memoryService;

    private final IAiAgentService agentService;

    private final AiSessionMapper sessionMapper;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final ThreadPoolTaskExecutor executor;

    public AiMemoryPipeline(AiMemoryExtractionService extractionService,
                            IAiMemoryService memoryService,
                            IAiAgentService agentService,
                            AiSessionMapper sessionMapper,
                            AiRuntimeConfigStore runtimeConfigStore) {
        this.extractionService = extractionService;
        this.memoryService = memoryService;
        this.agentService = agentService;
        this.sessionMapper = sessionMapper;
        this.runtimeConfigStore = runtimeConfigStore;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(200);
        pool.setThreadNamePrefix("ai-memory-pipeline-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
    }

    /**
     * 轮末触发：异步执行抽取与合并，不阻塞回答。
     *
     * @param sessionId 会话主键
     */
    public void onTurnCompleted(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        String mode = policy.getPipelineMode();
        boolean platform = "platform".equals(mode);
        boolean shadow = "shadow".equals(mode);
        if (!policy.isEnabled() || (!platform && !shadow)) {
            return;
        }
        try {
            executor.execute(() -> runQuietly(sessionId));
        } catch (Exception e) {
            // 队列满：直接丢弃本次触发，下一轮或每日维护兜底
            log.warn("平台记忆任务入队失败：sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 任务入口：任何异常只记日志，不能让线程池线程退出。
     */
    private void runQuietly(Long sessionId) {
        try {
            extractionService.extractIfDue(sessionId);
            // 影子模式只做抽取留样，不触发合并
            if ("platform".equals(runtimeConfigStore.memory().getPipelineMode())) {
                consolidateIfDue(sessionId);
            }
        } catch (Exception e) {
            log.warn("平台记忆管道执行失败：sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 自动合并：按用户节流 + 执行锁 + pending 判定。
     */
    private void consolidateIfDue(Long sessionId) {
        AiSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getUserId() == null || AiSession.SOURCE_TASK.equals(session.getSource())) {
            return;
        }
        AiAgent entry = agentService.getEntryAgent();
        if (entry == null) {
            return;
        }
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        Long userId = session.getUserId();
        String gateKey = CONSOLIDATE_GATE_PREFIX + entry.getId() + ":" + userId;
        if (!RedisUtils.setObjectIfAbsent(gateKey, "1",
            Duration.ofMinutes(Math.max(1, policy.getConsolidationMinGapMinutes())))) {
            return;
        }
        String lockKey = CONSOLIDATE_LOCK_PREFIX + entry.getId() + ":" + userId;
        if (!RedisUtils.setObjectIfAbsent(lockKey, "1", LOCK_TTL)) {
            return;
        }
        try {
            if (!memoryService.hasPendingLedger(entry.getId(), userId)) {
                return;
            }
            memoryService.consolidate(entry.getId(), userId, policy.getAutoConsolidateMaxBatches());
        } catch (Exception e) {
            // gate 保留：模型故障时避免每轮重试；每日维护会兜底
            log.warn("平台记忆自动合并失败：userId={}, err={}", userId, e.getMessage());
        } finally {
            RedisUtils.deleteObject(lockKey);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
