package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

/**
 * 继承槽位的读写（REQ-AI-31，Redis，不加表）。
 *
 * <p>两级存储与优先级：会话级 {@code bizbuddy:ai:slots:session:<sessionId>} **覆盖**人级
 * {@code bizbuddy:ai:slots:user:<userId>}，人级再覆盖**平台默认**（时区取
 * {@code bizbuddy.ai.chat.current-time-zone}、语言取平台中文口径）。</p>
 *
 * <p><b>写入仅来自平台</b>（登录态 / 时间中间件 / 页面设置），**模型无权改写**：故 {@link #merge}
 * 只读、{@link #setUserSlot} / {@link #setSessionSlot} 只应由平台侧调用，未暴露给任何模型可见的工具。</p>
 *
 * <p>无 TTL（会话级 / 人级均持久，取消即覆盖为空）；读取失败按「无槽位」降级，不影响对话。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSlotStore {

    /**
     * 人级槽位键前缀
     */
    private static final String USER_PREFIX = "bizbuddy:ai:slots:user:";

    /**
     * 会话级槽位键前缀（覆盖人级）
     */
    private static final String SESSION_PREFIX = "bizbuddy:ai:slots:session:";

    /**
     * 平台默认语言
     */
    private static final String DEFAULT_LOCALE = "zh-CN";

    private final AiProperties aiProperties;

    /**
     * 写入人级槽位（覆盖式）。
     *
     * @param userId 用户主键
     * @param slots  槽位（为空时删除该键）
     */
    public void setUserSlot(Long userId, AiSlotSet slots) {
        write(USER_PREFIX + userId, slots);
    }

    /**
     * 写入会话级槽位（覆盖式，优先级高于人级）。
     *
     * @param sessionId 会话主键
     * @param slots     槽位（为空时删除该键）
     */
    public void setSessionSlot(Long sessionId, AiSlotSet slots) {
        write(SESSION_PREFIX + sessionId, slots);
    }

    /**
     * 合并出本轮生效的槽位：平台默认 → 人级 → 会话级（后者非空字段覆盖前者）。
     *
     * @param userId    提问用户（可空）
     * @param sessionId 会话主键（可空）
     * @return 生效槽位；读取出错时退化为平台默认
     */
    public AiSlotSet merge(Long userId, Long sessionId) {
        AiSlotSet resolved = platformDefault();
        if (userId != null) {
            resolved = resolved.overlay(read(USER_PREFIX + userId));
        }
        if (sessionId != null) {
            resolved = resolved.overlay(read(SESSION_PREFIX + sessionId));
        }
        return resolved;
    }

    /**
     * 读取人级槽位原值（供页面回显；未设置或读取出错时为 {@code null}）。
     *
     * @param userId 用户主键（可空）
     * @return 人级槽位；未设置时为 {@code null}
     */
    public AiSlotSet getUserSlot(Long userId) {
        return userId == null ? null : read(USER_PREFIX + userId);
    }

    /**
     * 平台默认槽位（时区与语言由平台给定，时间窗 / 任务线默认未设置）。
     *
     * @return 平台默认槽位
     */
    public AiSlotSet platformDefault() {
        return new AiSlotSet(aiProperties.getChat().getCurrentTimeZone(), null, DEFAULT_LOCALE, null);
    }

    /**
     * 写入槽位（空则删除，避免留下过期覆盖）。
     *
     * @param key   键
     * @param slots 槽位
     */
    private void write(String key, AiSlotSet slots) {
        try {
            if (slots == null || slots.isEmpty()) {
                RedisUtils.deleteObject(key);
                return;
            }
            RedisUtils.setCacheObject(key, JsonUtils.toJsonString(slots));
        } catch (Exception e) {
            log.warn("写入继承槽位失败：key={}, {}", key, e.getMessage());
        }
    }

    /**
     * 读取槽位。
     *
     * @param key 键
     * @return 槽位；不存在 / 解析失败时返回 null
     */
    private AiSlotSet read(String key) {
        try {
            String json = RedisUtils.getCacheObject(key);
            return StringUtils.isBlank(json) ? null : JsonUtils.parseObject(json, AiSlotSet.class);
        } catch (Exception e) {
            log.warn("读取继承槽位失败（按无槽位处理）：key={}, {}", key, e.getMessage());
            return null;
        }
    }

}