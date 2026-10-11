package org.dromara.ai.core.channel;

import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的渠道入站事件幂等（REQ-AI-24 扩展：多副本可用）。
 *
 * <p>框架自带的 {@code IdempotencyStore} 是**单实例内存**实现，多副本部署下同一消息会
 * 被各副本各处理一次。本实现用 Redis {@code SETNX + TTL} 取代它：键为调用方传入的
 * {@code channelCode|事件ID}（前缀再加 {@code bizbuddy:ai:channel:seen:}），首个写入者放行、
 * 其余视为重复。</p>
 *
 * <p>Redis 不可用时**按放行处理**（幂等是防重，不是安全边界），只告警不阻断。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInboundEventDeduplicator implements InboundEventDeduplicator {

    /**
     * 幂等键前缀
     */
    private static final String KEY_PREFIX = "bizbuddy:ai:channel:seen:";

    private final AiProperties aiProperties;

    @Override
    public boolean firstSeen(String key) {
        if (StringUtils.isBlank(key)) {
            return true;
        }
        int ttlSeconds = Math.max(1, aiProperties.getChannel().getDedupTtlSeconds());
        try {
            return RedisUtils.setObjectIfAbsent(KEY_PREFIX + key,
                String.valueOf(System.currentTimeMillis()), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("渠道幂等判定异常，按放行处理：key={}, err={}", key, e.getMessage());
            return true;
        }
    }
}
