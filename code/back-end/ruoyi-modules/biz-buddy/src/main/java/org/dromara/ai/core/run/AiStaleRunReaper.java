package org.dromara.ai.core.run;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiAgentTaskRun;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.mapper.AiAgentTaskRunMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * 中断任务回收器：把长期停在「进行中」的记录落为终态。
 *
 * <p><b>要解决什么问题</b>：解析 / 向量化 / 编排 / 定时任务都在业务表里维护「进行中」状态，
 * 进程重启、容器被 kill 或工作线程异常退出后，这些状态不会自动回滚。后果是页面永远显示「处理中」，
 * 文档还会被后续的「正在解析中」守卫永久拒绝，只能人工改库。</p>
 *
 * <p><b>为什么用「更新时间 + 阈值」而不是启动时全部重置</b>：毫秒级一刀切在多副本部署时会误伤
 * 另一副本正在跑的任务；而状态写入都会刷新 {@code update_time}，因此「超过 N 分钟没有进展」
 * 才是可靠的死亡判据。阈值与扫描间隔均可通过 {@code bizbuddy.ai.reaper.*} 调整。</p>
 *
 * <p>所有回收都是**条件更新**（{@code where status = 进行中 and update_time < 阈值}），
 * 天然幂等，多副本同时扫描也不会互相覆盖。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiStaleRunReaper {

    /**
     * 文档解析状态：解析中 / 解析失败
     */
    private static final String DOC_PARSING = "1";
    private static final String DOC_PARSE_FAILED = "3";

    /**
     * 文档向量化状态：向量化中 / 向量化失败
     */
    private static final String DOC_VECTORIZING = "1";
    private static final String DOC_VECTOR_FAILED = "3";

    /**
     * 中断原因文案（页面直接展示，需说明「可以重试」）
     */
    static final String DOC_PARSE_MESSAGE = "任务因服务重启或长时间无进展被中断，请重新执行解析";
    static final String DOC_VECTOR_MESSAGE = "任务因服务重启或长时间无进展被中断，请重新执行向量化";
    static final String ROUTE_MESSAGE = "服务重启或长时间无进展，本次编排已中断";
    static final String TASK_MESSAGE = "服务重启或长时间无进展，本次执行已中断";

    /**
     * 最小扫描间隔（秒）：防止把配置误填成 0 导致空转
     */
    private static final int MIN_SCAN_INTERVAL_SECONDS = 30;

    private final AiDocumentMapper documentMapper;

    private final AiRouteRunMapper routeRunMapper;

    private final AiAgentTaskRunMapper taskRunMapper;

    private final AiProperties aiProperties;

    /**
     * 周期扫描调度器（单线程守护，避免与业务线程争抢）
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-stale-run-reaper");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 应用就绪后立即扫一次（吃掉重启残留），此后按配置周期扫描。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        AiProperties.Reaper config = aiProperties.getReaper();
        if (!config.isEnabled()) {
            log.info("中断任务回收器已关闭（bizbuddy.ai.reaper.enabled=false）");
            return;
        }
        int interval = Math.max(MIN_SCAN_INTERVAL_SECONDS, config.getScanIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::reapQuietly, 0, interval, TimeUnit.SECONDS);
        log.info("中断任务回收器已启动：staleMinutes={}，scanIntervalSeconds={}", config.getStaleMinutes(), interval);
    }

    /**
     * 执行一轮回收（幂等，可重复调用）。
     *
     * @return 本轮回收的记录数
     */
    public int reapNow() {
        AiProperties.Reaper config = aiProperties.getReaper();
        if (!config.isEnabled()) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(Math.max(1, config.getStaleMinutes()));
        int total = 0;
        total += safely("文档解析", () -> reapParsingDocuments(deadline));
        total += safely("文档向量化", () -> reapVectorizingDocuments(deadline));
        total += safely("编排运行", () -> reapRouteRuns(deadline));
        total += safely("定时任务执行", () -> reapTaskRuns(deadline));
        if (total > 0) {
            log.warn("已回收 {} 条中断的在跑记录（阈值 {} 分钟）", total, config.getStaleMinutes());
        }
        return total;
    }

    /**
     * 文档解析：解析中 → 解析失败（保留已生成切片，用户可直接重新解析）。
     *
     * <p><b>为什么用 wrapper 写 SET、不传实体</b>：实体更新会由自动填充刷新 {@code update_time}，
     * 同一文档若解析与向量化同时超期，先执行的那次更新会把时间刷新掉，后一个条件就再也命中不了。
     * 这里只写 SET 子句、不刷新时间，保证一轮扫描能同时回收两种状态。</p>
     *
     * @param deadline 逾期阈值
     * @return 回收条数
     */
    private int reapParsingDocuments(LocalDateTime deadline) {
        LambdaUpdateWrapper<AiDocument> wrapper = Wrappers.lambdaUpdate();
        wrapper.set(AiDocument::getParseStatus, DOC_PARSE_FAILED)
            .set(AiDocument::getErrorMsg, DOC_PARSE_MESSAGE)
            .eq(AiDocument::getParseStatus, DOC_PARSING)
            .lt(AiDocument::getUpdateTime, deadline);
        int count = documentMapper.update(null, wrapper);
        if (count > 0) {
            log.warn("回收中断的文档解析：{} 条", count);
        }
        return count;
    }

    /**
     * 文档向量化：向量化中 → 向量化失败（同样不刷新 {@code update_time}，理由见 {@link #reapParsingDocuments}）。
     *
     * @param deadline 逾期阈值
     * @return 回收条数
     */
    private int reapVectorizingDocuments(LocalDateTime deadline) {
        LambdaUpdateWrapper<AiDocument> wrapper = Wrappers.lambdaUpdate();
        wrapper.set(AiDocument::getVectorStatus, DOC_VECTOR_FAILED)
            .set(AiDocument::getVectorErrorMsg, DOC_VECTOR_MESSAGE)
            .eq(AiDocument::getVectorStatus, DOC_VECTORIZING)
            .lt(AiDocument::getUpdateTime, deadline);
        int count = documentMapper.update(null, wrapper);
        if (count > 0) {
            log.warn("回收中断的文档向量化：{} 条", count);
        }
        return count;
    }

    /**
     * 编排运行：running → aborted。
     *
     * @param deadline 逾期阈值
     * @return 回收条数
     */
    private int reapRouteRuns(LocalDateTime deadline) {
        AiRouteRun update = new AiRouteRun();
        update.setStatus(AiRouteRun.STATUS_ABORTED);
        update.setDegradeReason(ROUTE_MESSAGE);
        LambdaUpdateWrapper<AiRouteRun> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(AiRouteRun::getStatus, AiRouteRun.STATUS_RUNNING)
            .lt(AiRouteRun::getUpdateTime, deadline);
        int count = routeRunMapper.update(update, wrapper);
        if (count > 0) {
            log.warn("回收中断的编排运行：{} 条", count);
        }
        return count;
    }

    /**
     * 定时任务执行：执行中 → 失败（补写结束时间，便于页面展示终止时刻）。
     *
     * @param deadline 逾期阈值
     * @return 回收条数
     */
    private int reapTaskRuns(LocalDateTime deadline) {
        AiAgentTaskRun update = new AiAgentTaskRun();
        update.setStatus(AiAgentTaskRun.STATUS_FAILED);
        update.setErrorMsg(TASK_MESSAGE);
        update.setEndTime(LocalDateTime.now());
        LambdaUpdateWrapper<AiAgentTaskRun> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(AiAgentTaskRun::getStatus, AiAgentTaskRun.STATUS_RUNNING)
            .lt(AiAgentTaskRun::getUpdateTime, deadline);
        int count = taskRunMapper.update(update, wrapper);
        if (count > 0) {
            log.warn("回收中断的定时任务执行：{} 条", count);
        }
        return count;
    }

    /**
     * 单类回收失败不阻断其余类别（例如某张表暂时不可写）。
     *
     * @param scene  场景名（日志用）
     * @param action 回收动作
     * @return 回收条数，失败时 0
     */
    private int safely(String scene, IntSupplier action) {
        try {
            return action.getAsInt();
        } catch (Exception e) {
            log.warn("回收{}记录失败：{}", scene, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 周期任务入口：任何异常只记日志，不能让调度线程退出。
     */
    private void reapQuietly() {
        try {
            reapNow();
        } catch (Exception e) {
            log.warn("中断任务回收失败：{}", e.getMessage(), e);
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
