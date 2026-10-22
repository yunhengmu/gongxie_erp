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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆合并、归档与清理任务（REQ-AI-09）。
 *
 * <p>框架自带的维护依赖用户触发对话时才执行，长期不活跃的用户永远不会被处理；本任务按固定周期
 * 主动遍历「有会话历史的用户 ∪ 工作区已有记忆目录的用户」，对存在「即将归档且晚于合并水位」的
 * 用户先补一次合并，再按流水保留期归档，最后按各自保留期清理流水、归档与会话日志。</p>
 *
 * <p>顺序固定为「合并 → 归档 → 清理」：合并失败时跳过该用户的归档与清理，避免未合并的最后一轮
 * 流水被移出合并范围。只在长期记忆开关开启时执行；多副本部署用 Redis 键抢占，同一周期只允许
 * 一个实例真正扫描，扫描接近锁 TTL 时提前中止剩余用户。</p>
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

    /**
     * 扫描提前中止的预留时间（分钟）：留出清理与日志时间，避免锁过期后第二个实例并发扫描
     */
    private static final int LOCK_SAFETY_MINUTES = 5;

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
     * 执行一轮合并 + 归档 + 清理（幂等，可重复调用）。
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
        int lockMinutes = Math.min(interval, MAX_LOCK_MINUTES);
        // 抢占本轮执行权：其它实例在同一 TTL 内直接跳过
        boolean acquired = RedisUtils.setObjectIfAbsent(LOCK_KEY, "1", Duration.ofMinutes(lockMinutes));
        if (!acquired) {
            return 0;
        }
        AiAgent entry = agentService.getEntryAgent();
        if (entry == null) {
            return 0;
        }
        List<Long> userIds = collectUserIds(entry.getId());
        int affected = 0;
        int handled = 0;
        long started = System.currentTimeMillis();
        // 扫描接近锁 TTL 时提前中止，避免锁过期后第二个实例并发处理同一批用户
        long deadline = (lockMinutes - LOCK_SAFETY_MINUTES) * 60_000L;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            if (System.currentTimeMillis() - started > deadline) {
                log.warn("长期记忆维护扫描已接近锁有效期，提前中止：已处理 {}/{} 个用户",
                    handled, userIds.size());
                break;
            }
            if (!consolidateExpiring(entry.getId(), userId)) {
                // 合并失败：跳过归档与清理，避免未合并流水被移出合并范围
                continue;
            }
            handled++;
            try {
                affected += memoryService.archiveDaily(entry.getId(), userId);
                affected += memoryService.cleanup(entry.getId(), userId);
            } catch (Exception e) {
                log.warn("长期记忆维护失败：userId={}, err={}", userId, e.getMessage());
            }
        }
        if (affected > 0 || handled > 0) {
            log.info("长期记忆自动维护完成：扫描 {} 个用户，实际处理 {} 个，归档/清理 {} 个文件",
                userIds.size(), handled, affected);
        }
        return affected;
    }

    /**
     * 对「有即将归档流水且合并水位落后」的用户补一次合并。
     *
     * @param agentId 入口智能体主键
     * @param userId  用户主键
     * @return 是否可以继续归档（无需合并或合并成功返回 true；合并失败返回 false）
     */
    private boolean consolidateExpiring(Long agentId, Long userId) {
        try {
            if (!memoryService.hasUnconsolidatedExpiringLedger(agentId, userId)) {
                return true;
            }
            memoryService.consolidate(agentId, userId);
            return true;
        } catch (Exception e) {
            log.warn("长期记忆合并失败，跳过该用户归档：userId={}, err={}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 汇总需要维护的用户：会话表去重用户 ∪ 工作区中存在记忆数据的用户。
     *
     * @param agentId 入口智能体主键
     * @return 去重升序的用户主键列表
     */
    private List<Long> collectUserIds(Long agentId) {
        Set<Long> userIds = new TreeSet<>();
        List<Long> sessionUsers = sessionMapper.selectDistinctUserIds();
        if (sessionUsers != null) {
            sessionUsers.stream().filter(Objects::nonNull).forEach(userIds::add);
        }
        try {
            userIds.addAll(memoryService.listMemoryUserIds(agentId));
        } catch (Exception e) {
            // 记忆目录扫描失败不影响会话用户维护
            log.warn("扫描记忆目录用户失败：agentId={}, err={}", agentId, e.getMessage());
        }
        return new ArrayList<>(userIds);
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
