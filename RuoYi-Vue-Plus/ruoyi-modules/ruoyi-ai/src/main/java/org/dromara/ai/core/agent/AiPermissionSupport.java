package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiPermissionDecisionLog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限上下文改写支撑（REQ-AI-30）。
 *
 * <p>把某 {@code (userId, sessionId)} 槽位上**显式 ask 规则一并改写为 deny**：
 * 框架的 {@link PermissionMode#DONT_ASK} 只降级「未命中即 ASK」的默认档，对显式 ask 规则无效——
 * 实测仍会触发 {@code RequireUserConfirmEvent} 并让该轮停在等待确认上（任务看似结束但无任何回答）。</p>
 *
 * <p>改写只作用于该槽位，不影响共享单例上的其他会话。两条链路共用本方法：
 * 无人值守链路（定时任务 / 渠道接入）与编排链路的专家执行（专家是纯分析型，
 * {@code ask} 即拒绝、不悬挂）。</p>
 *
 * <p>另含用户级「记住该工具」信任的槽位合并（{@link #applyUserToolTrust}），入口智能体与专家执行
 * 两条链路共用同一实现、只按链路传入各自的装配基线与槽位键。</p>
 *
 * @author BizBuddy
 */
final class AiPermissionSupport {

    private AiPermissionSupport() {
    }

    /**
     * 把槽位上的 ask 规则改写为 deny，并把权限模式固定为 {@link PermissionMode#DONT_ASK}。
     *
     * @param agent     已装配的智能体
     * @param userId    用户标识（字符串形式，与 AgentScope 槽位键一致）
     * @param sessionId 会话标识（字符串形式）
     */
    static void denyAskingTools(HarnessAgent agent, String userId, String sessionId) {
        AgentState agentState = agent.getDelegate().getAgentState(userId, sessionId);
        PermissionContextState base = agentState.getPermissionContext();
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
        base.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        base.getAllowRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addAllowRule(tool, rule)));
        base.getDenyRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        base.getAskRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        agent.getDelegate().replacePermissionContext(userId, sessionId, builder.build());
    }

    /**
     * 把「用户已记住的工具」并入指定槽位的权限上下文。
     *
     * <p><b>为什么必须以管理端档位基线重建、而不是在现有上下文上叠加</b>：① 装配实例是
     * 智能体级、跨用户共享的，用户级信任只能落在 {@code (userId, sessionId)} 槽位；② 框架在每轮
     * call 开始时都会从状态存储重载 {@code AgentState} 并用其中的权限上下文重建权限引擎，
     * 因此恢复流里 {@code ConfirmResult} 附带的规则只对当轮有效；③ 「撤销记住」（删信任行）后必须
     * 让 ask 规则自动恢复——按基线重建即可自然做到（同名 ask 规则在已记住时不再生成，
     * 框架判定顺序为 deny &gt; ask &gt; 工具自检 &gt; allow，留着 ask 会让「记住」失效）。</p>
     *
     * <p>幂等：当前槽位已应用的 remember 规则集合与传入集合一致时直接返回，避免每轮重建并持久化会话状态。</p>
     *
     * <p><b>基线与槽位必须按链路给出</b>：入口智能体用「智能体 × 场景包」常规装配基线 +
     * 用户会话槽位；专家执行链路用<b>专家执行装配</b>基线（{@code permissionContextOfExpert}）+
     * {@code routeRunId#expertAgentId} 槽位——用错基线会让规则集合与实际装配的专家实例不一致。</p>
     *
     * @param agent     已装配的智能体
     * @param base      该链路对应的管理端档位权限上下文
     * @param trusted   该用户在该智能体上已记住的工具编码集合
     * @param userId    用户标识（字符串形式，与 AgentScope 槽位键一致）
     * @param sessionId 会话槽位标识（字符串形式）
     */
    static void applyUserToolTrust(HarnessAgent agent, PermissionContextState base, Set<String> trusted,
                                   String userId, String sessionId) {
        PermissionContextState current = agent.getDelegate().getAgentState(userId, sessionId).getPermissionContext();
        if (rememberAllowToolCodes(current).equals(trusted)) {
            // 已一致：无需重建（绝大多数轮次走这里）
            return;
        }
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(base.getMode());
        // 工作目录属运行期状态（可能被计划模式等追加），从当前槽位继承
        current.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        base.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        base.getAllowRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addAllowRule(tool, rule)));
        base.getDenyRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        base.getAskRules().forEach((tool, rules) -> {
            if (!trusted.contains(tool)) {
                rules.forEach(rule -> builder.addAskRule(tool, rule));
            }
        });
        for (String tool : trusted) {
            builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW,
                AiPermissionDecisionLog.SOURCE_REMEMBER));
        }
        agent.getDelegate().replacePermissionContext(userId, sessionId, builder.build());
    }

    /**
     * 取权限上下文里「记住来源（remember）」的 allow 工具集合。
     *
     * @param context 权限上下文
     * @return 已由「记住该工具」放行的工具编码集合
     */
    static Set<String> rememberAllowToolCodes(PermissionContextState context) {
        Set<String> codes = new LinkedHashSet<>();
        if (context == null) {
            return codes;
        }
        context.getAllowRules().forEach((tool, rules) -> {
            if (rules != null && rules.stream()
                .anyMatch(rule -> AiPermissionDecisionLog.SOURCE_REMEMBER.equals(rule.source()))) {
                codes.add(tool);
            }
        });
        return codes;
    }
}
