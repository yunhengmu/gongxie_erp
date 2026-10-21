package org.dromara.ai.core.memory;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆自动归档与清理任务（REQ-AI-09）。
 *
 * <p>框架自带的归档 / 合并维护依赖每个用户触发对话时才执行，长期不活跃的用户永远不会被清理；
 * 本任务按固定周期主动遍历「有会话历史的用户」，把超期每日流水移入 {@code memory/archive/}，
 * 再按各自的保留期清理流水、归档与会话日志。</p>
 *
 * <p>只在长期记忆开关开启时执行；多副本部署用 Redis 键抢占，同一周期只允许一个实例真正扫描。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemoryMaintenanceTask {

    /**
     * 分布式执行锁：键存在表示本轮已被某个实例占用
     */
    private static final String LOCK_KEY = "bizbuddy:ai:memory:maintenance-lock";

    /**
     * 应用启动后的首次执行延迟（分钟），避开启动高峰
     */
    private static final int INITIAL_DELAY_MINUTES = 5;

    /**
     * 最小执行间隔（分钟），防止配置误填导致空转
     */
    private static final int MIN_INTERVAL_MINUTES = 10;

    /**
     * 锁的最长保留时间（分钟）：覆盖一次扫描即可，不跟随执行间隔延长
     */
    private static final int MAX_LOCK_MINUTES = 120;

    private final AiProperties aiProperties;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final IAiAgentService agentService;

    private final IAiMemoryService memoryService;

    private final AiSessionMapper sessionMapper;

    /**
     * 周期调度器（单线程守护，避免与业务线程争抢）
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-memory-maintenance");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 应用就绪后按配置周期启动自动归档。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        AiProperties.Chat chat = aiProperties.getChat();
        if (!chat.isMemoryAutoArchiveEnabled()) {
            log.info("长期记忆自动归档已关闭（bizbuddy.ai.chat.memory-auto-archive-enabled=false）");
            return;
        }
        int interval = Math.max(MIN_INTERVAL_MINUTES, chat.getMemoryMaintenanceIntervalMinutes());
        scheduler.scheduleWithFixedDelay(this::runQuietly, INITIAL_DELAY_MINUTES, interval, TimeUnit.MINUTES);
        log.info("长期记忆自动归档已启动：intervalMinutes={}，initialDelayMinutes={}",
            interval, INITIAL_DELAY_MINUTES);
    }

    /**
     * 执行一轮归档 + 清理（幂等，可重复调用）。
     *
     * @return 本轮归档与删除的文件数
     */
    public int runNow() {
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        if (!policy.isEnabled()) {
            return 0;
        }
        int interval = Math.max(MIN_INTERVAL_MINUTES,
            aiProperties.getChat().getMemoryMaintenanceIntervalMinutes());
        // 抢占本轮执行权：其它实例在同一 TTL 内直接跳过
        boolean acquired = RedisUtils.setObjectIfAbsent(LOCK_KEY, "1",
            Duration.ofMinutes(Math.min(interval, MAX_LOCK_MINUTES)));
        if (!acquired) {
            return 0;
        }
        AiAgent entry = agentService.getEntryAgent();
        if (entry == null) {
            return 0;
        }
        List<Long> userIds = sessionMapper.selectDistinctUserIds();
        int affected = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            try {
                affected += memoryService.archiveDaily(entry.getId(), userId);
                affected += memoryService.cleanup(entry.getId(), userId);
            } catch (Exception e) {
                log.warn("长期记忆维护失败：userId={}, err={}", userId, e.getMessage());
            }
        }
        if (affected > 0) {
            log.info("长期记忆自动归档完成：扫描 {} 个用户，归档/清理 {} 个文件",
                userIds.size(), affected);
        }
        return affected;
    }

    /**
     * 周期任务入口：任何异常只记日志，不能让调度线程退出。
     */
    private void runQuietly() {
        try {
            runNow();
        } catch (Exception e) {
            log.warn("长期记忆自动归档失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 应用关闭时停止调度器。
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

}
