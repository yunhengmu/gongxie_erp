package org.dromara.ai.core.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 渠道级入站限流（REQ-AI-24 扩展：多副本可用，兼作防自环）。
 *
 * <p>框架自带的 {@code BotLoopGuard} 是**单实例内存**实现且为 {@code final} 不可扩展；
 * 本实现以 Redis 固定窗口计数 + 冷却取代它：按「渠道 × peer」分桶，窗口内事件数超过阈值
 * 即让该 peer 进入冷却，冷却期内一律拒绝。桶键包含渠道主键，故天然是「渠道级」限流，
 * 不同渠道互不影响。</p>
 *
 * <p>Redis 不可用时**按放行处理**（限流是保护，不是安全边界），只告警不阻断。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChannelRateLimiter {

    /**
     * 限流键前缀
     */
    private static final String KEY_PREFIX = "bizbuddy:ai:channel:rate:";

    private final AiProperties aiProperties;

    /**
     * 判定该渠道下该 peer 是否允许本次入站。
     *
     * @param channelCode 渠道编码（同时用于日志）
     * @param peerKey     会话 peer 标识（分桶键）
     * @return true 允许；false 超限或处于冷却期
     */
    public boolean allow(String channelCode, String peerKey) {
        if (StringUtils.isBlank(peerKey)) {
            return true;
        }
        AiProperties.Channel cfg = aiProperties.getChannel();
        String key = KEY_PREFIX + StringUtils.defaultString(channelCode) + ":" + peerKey;
        String cooldownKey = key + ":cool";
        try {
            if (Boolean.TRUE.equals(RedisUtils.getCacheObject(cooldownKey))) {
                return false;
            }
            long count = RedisUtils.incrAtomicValue(key);
            if (count == 1L) {
                RedisUtils.expire(key, Duration.ofSeconds(Math.max(1, cfg.getWindowSeconds())));
            }
            if (count > cfg.getMaxEventsPerWindow()) {
                RedisUtils.setCacheObject(cooldownKey, Boolean.TRUE,
                    Duration.ofSeconds(Math.max(1, cfg.getCooldownSeconds())));
                RedisUtils.deleteObject(key);
                log.warn("渠道入站触发限流并进入冷却：channel={}, peer={}, count={}", channelCode, peerKey, count);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("渠道限流判定异常，按放行处理：channel={}, err={}", channelCode, e.getMessage());
            return true;
        }
    }
}
