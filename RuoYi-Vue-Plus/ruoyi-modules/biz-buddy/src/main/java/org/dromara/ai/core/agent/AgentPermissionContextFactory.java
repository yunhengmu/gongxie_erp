package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.dromara.ai.domain.AiAgentTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配期权限上下文构建（REQ-AI-32）。
 *
 * <p>把各来源的授权决策翻译为 allow / ask / deny 规则，并补入平台默认放行项。从 {@link AgentRegistry}
 * 抽出：全部为无状态纯函数——入参已由调用方查库组装完毕，权限模式亦由调用方解析后传入，故不持有任何 Bean。</p>
 *
 * @author BizBuddy
 */
final class AgentPermissionContextFactory {

    /**
     * 子智能体编排与后台任务类**平台工具**（框架注册，不属于业务工具注册表）。
     *
     * <p>绑定子智能体时对这些工具追加 ALLOW 规则：它们只做「委派 / 收发任务 / 取结果」，
     * 本身不产生业务副作用——真正的副作用工具留在主智能体与子智能体的业务工具侧，
     * 且子智能体只能拿到只读工具。若不放行，委派动作会落入全局默认（未命中即 ASK），
     * 每委派一次都要人工确认，子智能体能力实际不可用（已实测：agent_spawn 触发 HITL 暂停）。</p>
     */
    private static final List<String> SUBAGENT_COORDINATION_TOOLS = List.of(
        "agent_spawn", "agent_send", "agent_list",
        "task_output", "task_cancel", "task_list", "wait_async_results");

    private AgentPermissionContextFactory() {
    }

    /**
     * 构建工具权限上下文：显式决策翻译为 allow / ask / deny 规则，未命中时按全局模式兜底。
     *
     * <p>决策来源有四处（REQ-AI-32 新增第 ④）：① 智能体 × 工具（{@code ai_agent_tool.decision}）；
     * ② 场景包 × 业务工具（{@code ai_scenario_pack_tool.decision}）；③ 场景包 × MCP 工具
     * （{@code ai_scenario_pack_mcp_tool.decision}，键为**归一化后**的工具名）；④ **会话级资源选择器**
     * 直接选中的工具（来源 {@code session}，档位取选中时的默认：只读 → 允许 / 写入 → 需确认）。
     * 优先级**越显式越优先**：直接选中 &gt; 包内 &gt; 智能体绑定。</p>
     *
     * <p><b>此处刻意不含「记住该工具」的用户级信任决策</b>：装配实例是智能体级、跨用户共享的，
     * 若把某个用户的信任写进共享上下文会让其他用户也被免确认；用户级信任由对话链路每轮按提问用户
     * 并入该 {@code (userId, sessionId)} 槽位的权限上下文（见 {@link AiTrustApplier}）。</p>
     *
     * @param mode              全局权限模式
     * @param bindings          智能体侧工具绑定（含授权决策）
     * @param packToolDecisions 场景包内业务工具的授权决策（可为空；多包时后者覆盖前者）
     * @param sessionToolDecisions 会话直接选中的业务工具决策（可为空；覆盖绑定与包内）
     * @param mcpDecisions      资源集内 MCP 工具的授权决策（归一化名 → 决策，可为空）
     * @param subagentsEnabled  是否绑定了启用中的子智能体（决定是否放行编排类平台工具）
     * @param entryAgent        是否入口智能体（决定是否放行 {@code route_decision}）
     * @return 权限上下文
     */
    static PermissionContextState buildContext(PermissionMode mode,
                                               List<AiAgentTool> bindings,
                                               Map<String, String> packToolDecisions,
                                               Map<String, String> sessionToolDecisions,
                                               Map<String, String> mcpDecisions,
                                               boolean subagentsEnabled,
                                               boolean entryAgent) {
        Map<String, String> sessionDecisions = sessionToolDecisions == null ? Map.of() : sessionToolDecisions;
        PermissionContextState.Builder builder = PermissionContextState.builder()
            .mode(mode);
        for (AiAgentTool bind : bindings) {
            // 同一工具被包内或会话直接选中显式覆盖时，以更显式者为准（不再生成智能体侧规则）
            if (packToolDecisions.containsKey(bind.getToolCode())
                || sessionDecisions.containsKey(bind.getToolCode())) {
                continue;
            }
            addRule(builder, bind.getToolCode(), bind.getDecision(), AgentRegistry.SOURCE_AGENT_TOOL);
        }
        for (Map.Entry<String, String> entry : packToolDecisions.entrySet()) {
            // 直接选中优先于包内
            if (sessionDecisions.containsKey(entry.getKey())) {
                continue;
            }
            addRule(builder, entry.getKey(), entry.getValue(), AgentRegistry.SOURCE_PACK);
        }
        for (Map.Entry<String, String> entry : sessionDecisions.entrySet()) {
            // 会话级资源选择器：直接选中，优先级最高
            addRule(builder, entry.getKey(), entry.getValue(), AgentRegistry.SOURCE_SESSION);
        }
        for (Map.Entry<String, String> entry : mcpDecisions.entrySet()) {
            addRule(builder, entry.getKey(), entry.getValue(), AgentRegistry.SOURCE_PACK);
        }
        for (String code : AgentRegistry.BUILTIN_READONLY_TOOL_CODES) {
            // 平台内置无害只读工具：未绑定时默认放行，避免落入全局模式（默认 DEFAULT＝未命中即 ASK）
            // 而对「看时间」这类无副作用操作弹确认卡片；已显式绑定的不覆盖其决策（含包内 / 直接选中决策）
            boolean bound = bindings.stream().anyMatch(bind -> code.equals(bind.getToolCode()))
                || packToolDecisions.containsKey(code) || sessionDecisions.containsKey(code)
                || mcpDecisions.containsKey(code);
            if (!bound) {
                builder.addAllowRule(code, new PermissionRule(code, null, PermissionBehavior.ALLOW,
                    AgentRegistry.SOURCE_BUILTIN));
            }
        }
        if (subagentsEnabled) {
            // 子智能体编排 / 后台任务类平台工具默认放行，否则每次委派都会弹人工确认（REQ-AI-04）
            for (String tool : SUBAGENT_COORDINATION_TOOLS) {
                builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW,
                    AgentRegistry.SOURCE_SUBAGENT));
            }
        }
        // 专家直答修订（2026-10-03）：专家侧不再有 submit_expert_result 协议工具（其回复直接交付用户），
        // 故此处只为入口侧的 route_decision 放行
        if (entryAgent) {
            builder.addAllowRule(RouteDecisionTool.TOOL_NAME, new PermissionRule(
                RouteDecisionTool.TOOL_NAME, null, PermissionBehavior.ALLOW, AgentRegistry.SOURCE_ROUTE));
        }
        return builder.build();
    }

    /**
     * 按三档决策追加权限规则（0 允许 / 1 需确认 / 2 禁止；其余值按「允许」兜底）。
     *
     * @param builder  权限上下文构建器
     * @param tool     工具名 / 工具编码
     * @param decision 决策值
     * @param source   规则来源标识
     */
    private static void addRule(PermissionContextState.Builder builder, String tool, String decision, String source) {
        if (AiAgentTool.DECISION_ASK.equals(decision)) {
            builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, source));
        } else if (AiAgentTool.DECISION_DENY.equals(decision)) {
            builder.addDenyRule(tool, new PermissionRule(tool, null, PermissionBehavior.DENY, source));
        } else {
            builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, source));
        }
    }

    /**
     * 合并「智能体绑定」与「包内工具」的授权决策：同一工具**以包内决策优先**。
     *
     * @param bindings          智能体侧工具绑定
     * @param packToolDecisions 包内工具决策（工具编码 → 决策）
     * @return 合并后的决策（保持「智能体在前、包内覆盖」的顺序）
     */
    static Map<String, String> mergeToolDecisions(List<AiAgentTool> bindings,
                                                  Map<String, String> packToolDecisions) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (AiAgentTool bind : bindings) {
            merged.put(bind.getToolCode(), bind.getDecision());
        }
        if (packToolDecisions != null) {
            merged.putAll(packToolDecisions);
        }
        return merged;
    }

    /**
     * 渲染工具决策（仅用于日志）。
     *
     * @param decisions 工具编码 → 决策
     * @return 形如 code=decision 的字符串
     */
    static String renderDecisions(Map<String, String> decisions) {
        StringBuilder sb = new StringBuilder();
        decisions.forEach((code, decision) -> {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(code).append('=').append(decision);
        });
        return sb.toString();
    }
}
