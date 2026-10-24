package org.dromara.ai.core.route;

import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JevCircuitBreaker} 单元测试：状态机（闭合 / 熔断 / 半开）、计数语义与故障兜底
 * （Redis 全部打桩，不连真实实例）。
 */
@DisplayName("结构化决策熔断器")
class JevCircuitBreakerTest {

    /**
     * 被测模型主键
     */
    private static final Long MODEL_ID = 9L;

    /**
     * 状态键前缀（与实现一致）
     */
    private static final String PREFIX = "bizbuddy:ai:jev:cb:";

    private final RedissonClient redissonClient = mock(RedissonClient.class);

    private final RBucket<Long> openBucket = mock(RBucket.class);
    private final RBucket<Long> armedBucket = mock(RBucket.class);
    private final RBucket<Long> probeBucket = mock(RBucket.class);
    private final RAtomicLong failCounter = mock(RAtomicLong.class);

    private final JevCircuitBreaker breaker = new JevCircuitBreaker(redissonClient);

    /**
     * 按模型主键接好四个状态键的桩（默认：均不存在、原子占位成功、计数为 0）。
     */
    @BeforeEach
    void stubKeys() {
        when(redissonClient.<Long>getBucket(PREFIX + "open:" + MODEL_ID)).thenReturn(openBucket);
        when(redissonClient.<Long>getBucket(PREFIX + "armed:" + MODEL_ID)).thenReturn(armedBucket);
        when(redissonClient.<Long>getBucket(PREFIX + "probe:" + MODEL_ID)).thenReturn(probeBucket);
        when(redissonClient.getAtomicLong(PREFIX + "fail:" + MODEL_ID)).thenReturn(failCounter);
        when(openBucket.isExists()).thenReturn(false);
        when(armedBucket.isExists()).thenReturn(false);
        when(probeBucket.setIfAbsent(anyLong(), any(Duration.class))).thenReturn(true);
    }

    /**
     * 构造启用熔断的配置（阈值 3、暂停 300 秒）。
     */
    private static AiJevConfigBo enabledConfig() {
        AiJevConfigBo cfg = new AiJevConfigBo();
        cfg.setCircuitBreakerEnabled(true);
        cfg.setCircuitBreakerThreshold(3);
        cfg.setCircuitBreakerPauseSeconds(300);
        return cfg;
    }

    @Test
    @DisplayName("未启用熔断时一律放行（不读写任何状态键）")
    void shouldAlwaysAllowWhenDisabled() {
        AiJevConfigBo cfg = enabledConfig();
        cfg.setCircuitBreakerEnabled(false);

        assertTrue(breaker.allow(MODEL_ID, cfg));
        verify(redissonClient, never()).getBucket(anyString());
    }

    @Test
    @DisplayName("从未熔断（无 open / armed）时放行")
    void shouldAllowWhenNeverTripped() {
        assertTrue(breaker.allow(MODEL_ID, enabledConfig()));
        // 未熔断过不应占用探测门
        verify(probeBucket, never()).setIfAbsent(anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("熔断期间拒绝（不外呼）")
    void shouldRejectWhenOpen() {
        when(openBucket.isExists()).thenReturn(true);

        assertFalse(breaker.allow(MODEL_ID, enabledConfig()));
    }

    @Test
    @DisplayName("半开：暂停期满后每探测窗口只放一个请求（原子占位成功者放行）")
    void shouldAllowOnlyOneProbeInHalfOpen() {
        when(armedBucket.isExists()).thenReturn(true);
        when(probeBucket.setIfAbsent(anyLong(), any(Duration.class))).thenReturn(true);

        assertTrue(breaker.allow(MODEL_ID, enabledConfig()));
    }

    @Test
    @DisplayName("半开：占位失败（已有探测在途）时按熔断处理")
    void shouldRejectWhenProbeInFlight() {
        when(armedBucket.isExists()).thenReturn(true);
        when(probeBucket.setIfAbsent(anyLong(), any(Duration.class))).thenReturn(false);

        assertFalse(breaker.allow(MODEL_ID, enabledConfig()));
    }

    @Test
    @DisplayName("连续失败未达阈值：只计数、不熔断")
    void shouldOnlyCountBelowThreshold() {
        when(failCounter.incrementAndGet()).thenReturn(2L);

        breaker.recordFailure(MODEL_ID, enabledConfig());

        verify(openBucket, never()).set(anyLong(), any(Duration.class));
        verify(failCounter, never()).set(0L);
    }

    @Test
    @DisplayName("连续失败达阈值：写入熔断与 armed 标记、计数器清零、清掉探测门")
    void shouldTripAtThreshold() {
        when(failCounter.incrementAndGet()).thenReturn(3L);

        breaker.recordFailure(MODEL_ID, enabledConfig());

        verify(openBucket).set(eq(1L), any(Duration.class));
        verify(armedBucket).set(eq(1L), any(Duration.class));
        verify(failCounter).set(0L);
        verify(probeBucket).delete();
    }

    @Test
    @DisplayName("成功即复位：计数器清零并清掉 open / armed / probe")
    void shouldResetOnSuccess() {
        breaker.recordSuccess(MODEL_ID, enabledConfig());

        verify(failCounter).set(0L);
        verify(openBucket).delete();
        verify(armedBucket).delete();
        verify(probeBucket).delete();
    }

    @Test
    @DisplayName("Redis 异常时按未熔断处理（熔断器自身故障不得阻断判定）")
    void shouldFailSafeWhenRedisThrows() {
        when(openBucket.isExists()).thenThrow(new RuntimeException("redis-down"));

        assertTrue(breaker.allow(MODEL_ID, enabledConfig()));
    }

    @Test
    @DisplayName("状态键按 modelConfigId 隔离")
    void shouldIsolateByModelConfigId() {
        RBucket<Long> otherModelOpen = mock(RBucket.class);
        when(otherModelOpen.isExists()).thenReturn(false);
        when(redissonClient.<Long>getBucket(PREFIX + "open:10")).thenReturn(otherModelOpen);
        when(redissonClient.<Long>getBucket(PREFIX + "armed:10")).thenReturn(mock(RBucket.class));
        when(openBucket.isExists()).thenReturn(true);

        assertFalse(breaker.allow(MODEL_ID, enabledConfig()));
        assertTrue(breaker.allow(10L, enabledConfig()));
    }

    @Test
    @DisplayName("模型主键为空时放行（无隔离键可写）")
    void shouldAllowWhenModelIdMissing() {
        assertTrue(breaker.allow(null, enabledConfig()));
        verify(redissonClient, never()).getBucket(anyString());
    }

    @Test
    @DisplayName("计数与复位在未启用时不触达 Redis")
    void shouldNotTouchRedisWhenDisabled() {
        AiJevConfigBo cfg = enabledConfig();
        cfg.setCircuitBreakerEnabled(false);

        breaker.recordFailure(MODEL_ID, cfg);
        breaker.recordSuccess(MODEL_ID, cfg);

        verify(failCounter, never()).incrementAndGet();
        verify(failCounter, never()).set(anyLong());
        verify(openBucket, never()).delete();
        verify(openBucket, never()).set(anyLong(), any(Duration.class));
    }

}
