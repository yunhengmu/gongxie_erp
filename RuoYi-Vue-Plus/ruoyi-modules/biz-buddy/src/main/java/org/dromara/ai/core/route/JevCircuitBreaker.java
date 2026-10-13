package org.dromara.ai.core.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 结构化决策外呼的熔断器（docs/22 §3.1 B）。
 *
 * <p><b>为什么需要它</b>：判定超时虽有上限（默认 1s），但如果提供方持续不可用，每一轮对话都会先付一次
 * 超时再回落——「故障放大」。熔断的作用是在**连续失败到阈值后直接跳过判定**，使提供方宕机期间
 * 判定环节的额外耗时归零。</p>
 *
 * <p><b>隔离粒度</b>：状态键按 {@code modelConfigId} 隔离（{@code bizbuddy:ai:jev:cb:*}），
 * 切换决策模型时不会被上一模型的失败拖累。</p>
 *
 * <p><b>状态机</b>（四个键：{@code fail} 连续失败计数 / {@code open} 熔断标记 /
 * {@code armed} 曾熔断标记 / {@code probe} 半开探测门）：</p>
 * <ol>
 *   <li><b>闭合</b>：无 {@code open}、无 {@code armed} → 放行；</li>
 *   <li><b>熔断</b>：{@code open} 存在（TTL = 暂停时长）→ 一律拒绝，不外呼；</li>
 *   <li><b>半开</b>：{@code open} 到期消失、但 {@code armed} 仍在 → 每个探测窗口只放
 *       <b>一个</b>请求（用 {@code setIfAbsent} 的原子语义保证），其余请求按熔断处理；
 *       探测成功即完全恢复，探测失败继续按「窗口内一个」节流探测。</li>
 * </ol>
 *
 * <p><b>计数语义</b>：{@code fail} 为**连续**失败计数——成功即清零，触发熔断后亦清零，故该键
 * 无需 TTL 也不会无界增长。</p>
 *
 * <p><b>与 {@code failOpen} 无关</b>：{@code failOpen} 当前是死开关（配置往返透传、无逻辑读取，
 * 见 docs/22 §3.1 B），本类不依赖它；未熔断时的单次失败仍按既有口径「回落工具决策」。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevCircuitBreaker {

    /**
     * 状态键前缀
     */
    private static final String PREFIX = "bizbuddy:ai:jev:cb:";

    /**
     * 半开探测窗口（秒）：窗口内最多放一个探测请求
     */
    private static final long PROBE_WINDOW_SECONDS = 30L;

    /**
     * 占位值（仅用于「键是否存在」，无业务含义）
     */
    private static final long MARK = 1L;

    private final RedissonClient redissonClient;

    /**
     * 判断本次是否允许外呼（含半开探测的放行判定）。
     *
     * @param modelConfigId 决策模型主键（状态键按它隔离）
     * @param cfg           结构化决策配置（取熔断开关）
     * @return 允许判定返回 true；熔断中（或半开且非本次探测）返回 false
     */
    public boolean allow(Long modelConfigId, AiJevConfigBo cfg) {
        if (modelConfigId == null || cfg == null || !cfg.isCircuitBreakerEnabled()) {
            return true;
        }
        try {
            if (bucket(openKey(modelConfigId)).isExists()) {
                return false;
            }
            if (!bucket(armedKey(modelConfigId)).isExists()) {
                // 从未熔断：正常放行
                return true;
            }
            // 熔断暂停期满后的半开：每个探测窗口只放一个请求（原子占位，避免并发探测打爆提供方）
            return bucket(probeKey(modelConfigId)).setIfAbsent(MARK,
                Duration.ofSeconds(PROBE_WINDOW_SECONDS));
        } catch (Exception e) {
            // 熔断器自身故障不得阻断判定：宁可照常外呼（等价「无熔断」）
            log.warn("结构化决策熔断器读取异常（按未熔断处理）：modelConfigId={}, err={}",
                modelConfigId, e.getMessage());
            return true;
        }
    }

    /**
     * 记录一次成功外呼：清零连续失败计数并解除熔断与半开标记。
     *
     * @param modelConfigId 决策模型主键
     * @param cfg           结构化决策配置
     */
    public void recordSuccess(Long modelConfigId, AiJevConfigBo cfg) {
        if (modelConfigId == null || cfg == null || !cfg.isCircuitBreakerEnabled()) {
            return;
        }
        try {
            atomic(failKey(modelConfigId)).set(0L);
            bucket(openKey(modelConfigId)).delete();
            bucket(armedKey(modelConfigId)).delete();
            bucket(probeKey(modelConfigId)).delete();
        } catch (Exception e) {
            log.warn("结构化决策熔断器复位异常（忽略）：modelConfigId={}, err={}", modelConfigId, e.getMessage());
        }
    }

    /**
     * 记录一次失败外呼：连续失败达到阈值即熔断，暂停 {@code circuitBreakerPauseSeconds} 秒。
     *
     * @param modelConfigId 决策模型主键
     * @param cfg           结构化决策配置（取阈值与暂停时长）
     */
    public void recordFailure(Long modelConfigId, AiJevConfigBo cfg) {
        if (modelConfigId == null || cfg == null || !cfg.isCircuitBreakerEnabled()) {
            return;
        }
        try {
            long failures = atomic(failKey(modelConfigId)).incrementAndGet();
            int threshold = Math.max(1, cfg.getCircuitBreakerThreshold());
            if (failures < threshold) {
                return;
            }
            long pauseSeconds = Math.max(1, cfg.getCircuitBreakerPauseSeconds());
            bucket(openKey(modelConfigId)).set(MARK, Duration.ofSeconds(pauseSeconds));
            // armed 的 TTL 长于暂停时长：保证暂停期满后仍能识别「处于半开」而非「从未熔断」
            bucket(armedKey(modelConfigId)).set(MARK, Duration.ofSeconds(pauseSeconds * 2));
            atomic(failKey(modelConfigId)).set(0L);
            // 触发熔断时清掉探测门，使暂停期满后的第一个请求能立刻充当探测
            bucket(probeKey(modelConfigId)).delete();
            log.warn("结构化决策已熔断：连续失败 {} 次，暂停 {} 秒（modelConfigId={}）",
                failures, pauseSeconds, modelConfigId);
        } catch (Exception e) {
            log.warn("结构化决策熔断器计数异常（忽略）：modelConfigId={}, err={}", modelConfigId, e.getMessage());
        }
    }

    private RBucket<Long> bucket(String key) {
        return redissonClient.getBucket(key);
    }

    private RAtomicLong atomic(String key) {
        return redissonClient.getAtomicLong(key);
    }

    private static String failKey(Long id) {
        return PREFIX + "fail:" + id;
    }

    private static String openKey(Long id) {
        return PREFIX + "open:" + id;
    }

    private static String armedKey(Long id) {
        return PREFIX + "armed:" + id;
    }

    private static String probeKey(Long id) {
        return PREFIX + "probe:" + id;
    }

}
