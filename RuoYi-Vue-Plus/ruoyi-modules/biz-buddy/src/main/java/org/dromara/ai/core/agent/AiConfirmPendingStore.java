package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HITL 待确认工具调用的暂存（Redis）。
 *
 * <p>首段流因「需人工确认」暂停时写入；用户在对话页确认 / 拒绝后回写决策并保留至恢复流结束。
 * 键带 TTL，超时后确认入口会明确提示「已过期」，避免出现无法操作的悬挂卡片。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConfirmPendingStore {

    /**
     * 键前缀：bizbuddy:ai:confirm:pending:{sessionId}:{messageId}
     */
    private static final String KEY_PREFIX = "bizbuddy:ai:confirm:pending:";

    /**
     * 键前缀（恢复占用）：bizbuddy:ai:confirm:claimed:{sessionId}:{messageId}
     *
     * <p>一次性占用键：同一张确认卡片被重复提交 / 恢复流被重复打开时，只允许一路进入恢复执行。</p>
     */
    private static final String CLAIM_PREFIX = "bizbuddy:ai:confirm:claimed:";

    private final AiProperties aiProperties;

    /**
     * 保存待确认状态（TTL 以配置为准）。
     *
     * @param pending 待确认状态
     */
    public void save(PendingConfirm pending) {
        RedisUtils.setCacheObject(key(pending.sessionId(), pending.messageId()), JsonUtils.toJsonString(pending),
            Duration.ofMinutes(aiProperties.getChat().getPendingConfirmTtlMinutes()));
    }

    /**
     * 读取待确认状态。
     *
     * @param sessionId 会话主键
     * @param messageId 待确认的助手消息主键
     * @return 待确认状态；不存在或已过期时为空
     */
    public Optional<PendingConfirm> get(Long sessionId, Long messageId) {
        String json = RedisUtils.getCacheObject(key(sessionId, messageId));
        if (StringUtils.isBlank(json)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JsonUtils.parseObject(json, PendingConfirm.class));
        } catch (Exception e) {
            log.warn("待确认状态解析失败，按已过期处理：sessionId={}, messageId={}", sessionId, messageId, e);
            return Optional.empty();
        }
    }

    /**
     * 删除待确认状态（恢复完成或用户操作结束后）。
     *
     * @param sessionId 会话主键
     * @param messageId 助手消息主键
     */
    public void remove(Long sessionId, Long messageId) {
        RedisUtils.deleteObject(key(sessionId, messageId));
    }

    /**
     * 占用恢复入口（一次性）：成功返回 true，已被占用返回 false。
     *
     * <p>用于并发保护——用户重复点击确认、或在多个标签页同时打开恢复流时，只放行一路执行。</p>
     *
     * @param sessionId 会话主键
     * @param messageId 待确认的助手消息主键
     * @return 是否占用成功
     */
    public boolean claim(Long sessionId, Long messageId) {
        Boolean acquired = RedisUtils.setObjectIfAbsent(CLAIM_PREFIX + sessionId + ":" + messageId, "1",
            Duration.ofMinutes(aiProperties.getChat().getPendingConfirmTtlMinutes()));
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 释放恢复入口占用（本次恢复段结束即释放：暂停后还要允许用户再次确认，故不能长期占用）。
     *
     * @param sessionId 会话主键
     * @param messageId 待确认的助手消息主键
     */
    public void release(Long sessionId, Long messageId) {
        RedisUtils.deleteObject(CLAIM_PREFIX + sessionId + ":" + messageId);
    }

    /**
     * 生成键。
     *
     * @param sessionId 会话主键
     * @param messageId 助手消息主键
     * @return Redis 键
     */
    private String key(Long sessionId, Long messageId) {
        return KEY_PREFIX + sessionId + ":" + messageId;
    }

    /**
     * 待确认的工具调用。
     *
     * @param toolCallId AgentScope 工具调用 ID
     * @param toolName   工具名称
     * @param input      工具入参
     * @param content    AgentScope 工具调用的原始 content；恢复执行时框架按此字段校验入参，缺失会报
     *                   {@code argument "content" is null}
     */
    public record PendingToolCall(String toolCallId, String toolName, Map<String, Object> input, String content) {
    }

    /**
     * 用户对待确认工具的处置结果。
     *
     * @param toolCallId AgentScope 工具调用 ID
     * @param confirmed  是否确认执行
     * @param remember   是否记住（本次确认后改写该智能体 × 工具的授权决策为「允许」）
     */
    public record PendingDecision(String toolCallId, boolean confirmed, boolean remember) {
    }

    /**
     * 待确认状态。
     *
     * @param sessionId    会话主键
     * @param messageId    助手消息主键（暂停时落库）
     * @param agentId      发起该待确认工具调用的智能体主键（**专家链路的暂停为专家主键**，否则为会话智能体）
     * @param userId       提问用户
     * @param replyId      AgentScope replyId
     * @param toolCalls    待确认的工具调用
     * @param decisions    用户处置结果（确认接口写入，恢复流读取）
     * @param createdAt    创建时间（ISO-8601）
     * @param expertResume 专家续跑信息；为 {@code null} 表示这是入口智能体自己的暂停（走既有入口恢复路径）
     */
    public record PendingConfirm(Long sessionId, Long messageId, Long agentId, Long userId, String replyId,
                                 List<PendingToolCall> toolCalls, List<PendingDecision> decisions, String createdAt,
                                 ExpertResume expertResume) {
    }

    /**
     * 专家续跑信息（REQ-AI-30 Phase 2：编排链路的 HITL 贯通）。
     *
     * <p>专家命中「需确认」工具时，框架会暂停**专家自己的会话**（键为 {@code (userId, expertSessionId)}）；
     * 用户确认后必须用**同一专家实例 + 同一 {@code expertSessionId}** 携带 {@code ConfirmResult} 才能续跑。
     * 故把续跑所需上下文随待确认状态一并暂存。</p>
     *
     * <p><b>专家直答修订（2026-10-03）</b>：已无输出契约与重试，故不再暂存 {@code contract} /
     * {@code lenient} / {@code previousError}。</p>
     *
     * @param routeRunId      编排主键（续跑继续记在同一次编排上）
     * @param expertAgentId   专家智能体主键
     * @param expertSessionId 专家会话标识（{@code routeRunId#expertAgentId}）
     * @param taskContext     下发给专家的任务片段（续跑审计留档用）
     * @param attemptNo       当前尝试序号（续跑沿用该序号）
     * @param callKind        当前调用类型（first / resume）
     */
    public record ExpertResume(Long routeRunId, Long expertAgentId, String expertSessionId,
                               String taskContext, Integer attemptNo, String callKind) {
    }

}