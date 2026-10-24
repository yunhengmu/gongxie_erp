package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.dromara.ai.core.agent.tools.ForgetUserPreferenceTool;
import org.dromara.ai.core.agent.tools.GetCurrentTimeTool;
import org.dromara.ai.core.agent.tools.GetCurrentUserTool;
import org.dromara.ai.core.agent.tools.RememberUserPreferenceTool;
import org.dromara.ai.domain.AiAgentTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentPermissionContextFactory} 单元测试：决策合并优先级、内置只读放行、子智能体 / 入口放行与日志渲染。
 *
 * <p>装配期权限上下文的核心逻辑已由 {@link AgentRegistryTest} 从公开 API 侧覆盖；本类补充
 * {@code mergeToolDecisions} / {@code renderDecisions} 这两个不对外暴露的纯函数的直接断言。</p>
 */
@DisplayName("装配期权限上下文构建")
class AgentPermissionContextFactoryTest {

    private static AiAgentTool bind(String toolCode, String decision) {
        AiAgentTool bind = new AiAgentTool();
        bind.setToolCode(toolCode);
        bind.setDecision(decision);
        return bind;
    }

    @Test
    @DisplayName("决策合并：保持绑定顺序，包内决策覆盖同名绑定")
    void shouldMergeWithPackOverride() {
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("t_a", AiAgentTool.DECISION_ALLOW);
        pack.put("t_new", AiAgentTool.DECISION_DENY);

        Map<String, String> merged = AgentPermissionContextFactory.mergeToolDecisions(
            List.of(bind("t_a", AiAgentTool.DECISION_ASK), bind("t_b", AiAgentTool.DECISION_ASK)), pack);

        assertEquals(List.of("t_a", "t_b", "t_new"), List.copyOf(merged.keySet()));
        assertEquals(AiAgentTool.DECISION_ALLOW, merged.get("t_a"));
        assertEquals(AiAgentTool.DECISION_ASK, merged.get("t_b"));
        assertEquals(AiAgentTool.DECISION_DENY, merged.get("t_new"));
    }

    @Test
    @DisplayName("决策合并：包内为 null 时只取绑定")
    void shouldMergeWithNullPack() {
        Map<String, String> merged = AgentPermissionContextFactory.mergeToolDecisions(
            List.of(bind("t_a", AiAgentTool.DECISION_ASK)), null);
        assertEquals(Map.of("t_a", AiAgentTool.DECISION_ASK), merged);
    }

    @Test
    @DisplayName("决策渲染：code=decision 逗号分隔")
    void shouldRenderDecisions() {
        Map<String, String> decisions = new LinkedHashMap<>();
        decisions.put("t_a", "0");
        decisions.put("t_b", "1");
        assertEquals("t_a=0,t_b=1", AgentPermissionContextFactory.renderDecisions(decisions));
        assertEquals("", AgentPermissionContextFactory.renderDecisions(Map.of()));
    }

    @Test
    @DisplayName("上下文构建：直接选中 > 包内 > 绑定，内置只读默认放行")
    void shouldBuildWithMergePriority() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(bind("t_bind", AiAgentTool.DECISION_ASK), bind("t_both", AiAgentTool.DECISION_DENY)),
            Map.of("t_both", AiAgentTool.DECISION_ASK, "t_pack", AiAgentTool.DECISION_ALLOW),
            Map.of("t_both", AiAgentTool.DECISION_ALLOW, "t_direct", AiAgentTool.DECISION_DENY),
            Map.of(), false, false, false);

        assertEquals(PermissionMode.DEFAULT, ctx.getMode());
        assertTrue(ctx.getAskRules().containsKey("t_bind"));
        assertEquals(AgentRegistry.SOURCE_AGENT_TOOL, ctx.getAskRules().get("t_bind").get(0).source());
        assertTrue(ctx.getAllowRules().containsKey("t_both"));
        assertEquals(AgentRegistry.SOURCE_SESSION, ctx.getAllowRules().get("t_both").get(0).source());
        assertTrue(ctx.getAllowRules().containsKey("t_pack"));
        assertEquals(AgentRegistry.SOURCE_PACK, ctx.getAllowRules().get("t_pack").get(0).source());
        assertTrue(ctx.getDenyRules().containsKey("t_direct"));
        assertEquals(AgentRegistry.SOURCE_SESSION, ctx.getDenyRules().get("t_direct").get(0).source());
        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_READONLY_TOOL_CODES));
        assertEquals(AgentRegistry.SOURCE_BUILTIN,
            ctx.getAllowRules().get(GetCurrentTimeTool.TOOL_NAME).get(0).source());
    }

    @Test
    @DisplayName("上下文构建：会话决策为 null 视为空集")
    void shouldTreatNullSessionDecisionsAsEmpty() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(), Map.of(), null, Map.of(), false, false, false);
        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_READONLY_TOOL_CODES));
    }

    @Test
    @DisplayName("上下文构建：显式绑定的内置只读不套用默认放行")
    void shouldNotApplyBuiltinDefaultWhenExplicitlyBound() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(bind(GetCurrentTimeTool.TOOL_NAME, AiAgentTool.DECISION_DENY)),
            Map.of(), Map.of(), Map.of(), false, false, false);
        assertTrue(ctx.getDenyRules().containsKey(GetCurrentTimeTool.TOOL_NAME));
        assertFalse(ctx.getAllowRules().containsKey(GetCurrentTimeTool.TOOL_NAME));
        assertTrue(ctx.getAllowRules().containsKey(GetCurrentUserTool.TOOL_NAME));
    }

    @Test
    @DisplayName("上下文构建：子智能体协调工具与入口路由工具放行")
    void shouldAllowSubagentAndRouteTools() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(), Map.of(), Map.of(), Map.of(), true, true, false);
        assertEquals(AgentRegistry.SOURCE_SUBAGENT, ctx.getAllowRules().get("agent_spawn").get(0).source());
        assertTrue(ctx.getAllowRules().containsKey("wait_async_results"));
        assertEquals(AgentRegistry.SOURCE_ROUTE,
            ctx.getAllowRules().get(RouteDecisionTool.TOOL_NAME).get(0).source());
    }

    @Test
    @DisplayName("上下文构建：专家模式（子智能体关闭）不放行协调与路由工具")
    void shouldNotAllowSubagentOrRouteWhenDisabled() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DONT_ASK,
            List.of(), Map.of(), Map.of(), Map.of(), false, false, false);
        assertEquals(PermissionMode.DONT_ASK, ctx.getMode());
        assertFalse(ctx.getAllowRules().containsKey("agent_spawn"));
        assertFalse(ctx.getAllowRules().containsKey(RouteDecisionTool.TOOL_NAME));
    }

    @Test
    @DisplayName("上下文构建：MCP 决策以场景包来源并入")
    void shouldAddMcpDecisionsWithPackSource() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(), Map.of(), Map.of(), Map.of("mcp_a", AiAgentTool.DECISION_ASK), false, false, false);
        assertTrue(ctx.getAskRules().containsKey("mcp_a"));
        assertEquals(AgentRegistry.SOURCE_PACK, ctx.getAskRules().get("mcp_a").get(0).source());
    }

    @Test
    @DisplayName("上下文构建：平台托管显式记忆工具默认放行")
    void shouldAllowDeclaredMemoryToolsWhenEnabled() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(), Map.of(), Map.of(), Map.of(), false, true, true);
        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_MEMORY_TOOL_CODES));
        assertEquals(AgentRegistry.SOURCE_MEMORY,
            ctx.getAllowRules().get(RememberUserPreferenceTool.TOOL_NAME).get(0).source());
    }

    @Test
    @DisplayName("上下文构建：未启用平台托管时不放行显式记忆工具")
    void shouldNotAllowDeclaredMemoryToolsWhenDisabled() {
        PermissionContextState ctx = AgentPermissionContextFactory.buildContext(PermissionMode.DEFAULT,
            List.of(), Map.of(), Map.of(), Map.of(), false, true, false);
        assertFalse(ctx.getAllowRules().containsKey(RememberUserPreferenceTool.TOOL_NAME));
        assertFalse(ctx.getAllowRules().containsKey(ForgetUserPreferenceTool.TOOL_NAME));
    }
}
