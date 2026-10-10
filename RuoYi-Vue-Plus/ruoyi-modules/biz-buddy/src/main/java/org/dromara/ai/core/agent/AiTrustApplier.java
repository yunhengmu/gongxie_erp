package org.dromara.ai.core.agent;

import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 「记住该工具」用户级信任的落位（入口链路与专家链路）。
 *
 * <p>把用户已记住的工具并入对应会话槽位的权限上下文：入口链路用用户会话槽位，
 * 专家链路用 {@code routeRunId#expertAgentId} 槽位，且各自取对应装配的「管理端档位」为基线。
 * 由门面、HITL 确认与专家恢复段共用，故独立为横切组件。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AiTrustApplier {

    private final AgentRegistry agentRegistry;

    private final IAiAgentService agentService;

    /**
     * 把信任并入用户会话槽位（{@code (userId, sessionId)}）。
     *
     * @param agent      已装配的智能体
     * @param definition 智能体定义
     * @param resources  会话级资源集（REQ-AI-32；用于取与装配一致的「管理端档位」基线）
     * @param userId     提问用户主键
     * @param sessionId  会话主键（可空）
     */
    void apply(HarnessAgent agent, AiAgent definition, AiSessionResourceSet resources,
               Long userId, Long sessionId) {
        applyToSessionKey(agent, definition, resources, userId, sessionId == null ? null : String.valueOf(sessionId));
    }

    /**
     * 把信任并入指定会话槽位（字符串键版本）。
     *
     * <p>专家链路的会话键不是雪花主键，而是 {@code routeRunId#expertAgentId} 这样的字符串，
     * 故单独提供字符串版本（REQ-AI-30 Phase 2 HITL 贯通：确认卡片上的「记住该工具」要落到专家槽位）。</p>
     *
     * @param agent        已装配的智能体
     * @param definition   智能体定义
     * @param resources    会话级资源集（REQ-AI-32）
     * @param userId       提问用户
     * @param sessionKey   AgentScope 的 sessionId 字符串键
     */
    void applyToSessionKey(HarnessAgent agent, AiAgent definition, AiSessionResourceSet resources,
                           Long userId, String sessionKey) {
        if (agent == null || definition == null || userId == null || StringUtils.isBlank(sessionKey)) {
            return;
        }
        AiPermissionSupport.applyUserToolTrust(agent, agentRegistry.permissionContextOf(definition, resources),
            agentService.trustedToolCodes(definition.getId(), userId), String.valueOf(userId), sessionKey);
    }

    /**
     * 把「该用户在该专家上已记住的工具」并入**专家会话槽位**（REQ-AI-30 Phase 2 HITL 贯通）。
     *
     * <p>与入口链路的差别只在两处：槽位键是 {@code routeRunId#expertAgentId} 字符串，
     * 基线取<b>专家执行装配</b>的权限上下文（子智能体协调工具与编排协议工具均不放行）。</p>
     *
     * @param agent          已装配的专家实例（{@code getExpert}）
     * @param definition     专家定义
     * @param resources      会话级资源集（REQ-AI-32）
     * @param userId         提问用户
     * @param expertSessionId 专家会话标识
     */
    void applyExpert(HarnessAgent agent, AiAgent definition, AiSessionResourceSet resources,
                     Long userId, String expertSessionId) {
        if (agent == null || definition == null || userId == null || StringUtils.isBlank(expertSessionId)) {
            return;
        }
        AiPermissionSupport.applyUserToolTrust(agent,
            agentRegistry.permissionContextOfExpert(definition, resources),
            agentService.trustedToolCodes(definition.getId(), userId), String.valueOf(userId), expertSessionId);
    }
}
