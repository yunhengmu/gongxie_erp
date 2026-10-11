package org.dromara.ai.core.channel;

import org.dromara.ai.config.AiProperties;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * 渠道分布式保障单测（REQ-AI-24 扩展）：Redis 幂等与渠道级限流。
 *
 * <p>用 {@code mockStatic(RedisUtils.class)} 覆盖三件事：窗口内放行 / 超限进入冷却、
 * 幂等首见放行与重复丢弃、以及 **Redis 不可用时按放行处理**（fail-open，不阻断入站）。</p>
 *
 * @author BizBuddy
 */
@DisplayName("渠道分布式保障：幂等与限流")
class AiChannelDistributedGuardTest {

    private static final String RATE_KEY = "bizbuddy:ai:channel:rate:ch-1:peer-1";
    private static final String COOLDOWN_KEY = RATE_KEY + ":cool";
    private static final String SEEN_KEY = "bizbuddy:ai:channel:seen:ch-1|m1";

    private final AiProperties properties = new AiProperties();

    @Test
    @DisplayName("限流：窗口内放行")
    void rateLimiterAllowsWithinWindow() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(COOLDOWN_KEY)).thenReturn(null);
            redis.when(() -> RedisUtils.incrAtomicValue(RATE_KEY)).thenReturn(1L);

            assertTrue(new AiChannelRateLimiter(properties).allow("ch-1", "peer-1"));
            redis.verify(() -> RedisUtils.expire(eq(RATE_KEY), any(Duration.class)));
        }
    }

    @Test
    @DisplayName("限流：超限进入冷却并拒绝")
    void rateLimiterRejectsWhenOverLimit() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(COOLDOWN_KEY)).thenReturn(null);
            // 阈值 20：第 21 次即超限
            redis.when(() -> RedisUtils.incrAtomicValue(RATE_KEY)).thenReturn(21L);

            assertFalse(new AiChannelRateLimiter(properties).allow("ch-1", "peer-1"));
            redis.verify(() -> RedisUtils.setCacheObject(eq(COOLDOWN_KEY), eq(Boolean.TRUE), any(Duration.class)));
        }
    }

    @Test
    @DisplayName("限流：冷却期内直接拒绝，不再计数")
    void rateLimiterRejectsDuringCooldown() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(COOLDOWN_KEY)).thenReturn(Boolean.TRUE);

            assertFalse(new AiChannelRateLimiter(properties).allow("ch-1", "peer-1"));
        }
    }

    @Test
    @DisplayName("限流：Redis 不可用时放行（fail-open）")
    void rateLimiterFailsOpen() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString()))
                .thenThrow(new RuntimeException("redis down"));

            assertTrue(new AiChannelRateLimiter(properties).allow("ch-1", "peer-1"));
        }
    }

    @Test
    @DisplayName("幂等：首次放行、重复丢弃")
    void deduplicatorKeepsFirstSeen() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(eq(SEEN_KEY), anyString(), any(Duration.class)))
                .thenReturn(true);
            RedisInboundEventDeduplicator dedup = new RedisInboundEventDeduplicator(properties);
            assertTrue(dedup.firstSeen("ch-1|m1"));

            redis.when(() -> RedisUtils.setObjectIfAbsent(eq(SEEN_KEY), anyString(), any(Duration.class)))
                .thenReturn(false);
            assertFalse(dedup.firstSeen("ch-1|m1"));
        }
    }

    @Test
    @DisplayName("幂等：Redis 不可用时视为首次（fail-open）")
    void deduplicatorFailsOpen() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

            assertTrue(new RedisInboundEventDeduplicator(properties).firstSeen("ch-1|m1"));
        }
    }
}
