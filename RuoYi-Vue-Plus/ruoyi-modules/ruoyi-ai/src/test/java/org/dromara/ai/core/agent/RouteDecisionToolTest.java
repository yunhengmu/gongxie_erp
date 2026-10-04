package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RouteDecisionTool} 单元测试：编排链路的护栏分支与专家编排执行。
 */
@DisplayName("路由决策工具")
class RouteDecisionToolTest {

    private final AiRouteOrchestrator orchestrator = mock(AiRouteOrchestrator.class);
    private final RouteDecisionTool tool = new RouteDecisionTool(orchestrator);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    /**
     * 构造携带编排上下文的工具调用参数。
     */
    private static RuntimeContext routeContext(AiRouteContext route) {
        return RuntimeContext.builder().userId("100").put(AiRouteContext.class, route).build();
    }

    private static AiRouteContext newRoute() {
        AiExpertCandidate candidate =
            new AiExpertCandidate(1L, "finance_agent", "财务专家", "finance", "擅长财务");
        return new AiRouteContext(1L, 2L, 100L, 3L, 4L, List.of(candidate), 5L, null);
    }

    @Test
    @DisplayName("不在编排链路时回绝")
    void shouldRejectWhenNoRouteContext() {
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of("action", "self"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("当前不在编排链路"));
    }

    @Test
    @DisplayName("整合轮禁止再路由")
    void shouldRejectWhenRouteDone() {
        AiRouteContext route = newRoute();
        route.routeDone = true;
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of(), routeContext(route))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("本轮为整合轮"));
    }

    @Test
    @DisplayName("等待确认期间不再接受新路由")
    void shouldRejectWhenPendingResume() {
        AiRouteContext route = newRoute();
        route.pendingResume = new AiConfirmPendingStore.ExpertResume(1L, 1L, "s#1", "task", 1, "first");
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of(), routeContext(route))).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("正在等待用户确认"));
    }

    @Test
    @DisplayName("专家直答接管后不再路由，并给出可收敛的终止动作")
    void shouldRejectWhenHandoff() {
        AiRouteContext route = newRoute();
        route.handoff = true;
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of(), routeContext(route))).block();
        assertFalse(isError(result));
        // 护栏必须同时给出「停止路由」与「输出纯文本以结束循环」两个信号：
        // 只说「不要再输出任何内容」会让模型无终止动作可做，从而反复调用本工具直到 maxIters
        assertTrue(text(result).contains("已直接回答用户"));
        assertTrue(text(result).contains("停止调用工具"));
    }

    @Test
    @DisplayName("action=self 时记录自答决定")
    void shouldRecordSelfDecision() {
        AiRouteContext route = newRoute();
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of("action", "self"), routeContext(route))).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("本轮由你自己回答"));
        assertEquals(AiRouteRun.ACTION_SELF, route.action);
        verify(orchestrator, never()).execute(any(), any());
    }

    @Test
    @DisplayName("action 非法时降级")
    void shouldDegradeWhenActionInvalid() {
        AiRouteContext route = newRoute();
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of("action", "bad"), routeContext(route))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("action 只能是 self 或 route"));
        assertTrue(route.degraded);
    }

    @Test
    @DisplayName("候选专家不在清单内时降级")
    void shouldDegradeWhenCandidateUnknown() {
        AiRouteContext route = newRoute();
        Map<String, Object> input = Map.of("action", "route", "expert_agent_key", "unknown");
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, input, routeContext(route))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("必须来自本轮候选专家清单"));
        assertTrue(route.degraded);
    }

    @Test
    @DisplayName("路由成功时回喂编排报告")
    void shouldReturnOrchestratorReport() {
        AiRouteContext route = newRoute();
        when(orchestrator.execute(any(AiRouteContext.class), any(AiExpertCandidate.class)))
            .thenReturn("【专家已直接回答用户】财务专家（finance_agent）");
        Map<String, Object> input = Map.of("action", "route", "expert_agent_key", "finance_agent",
            "task_context", "帮我算一下");
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, input, routeContext(route))).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("专家已直接回答用户"));
        assertEquals("帮我算一下", route.taskContext);
    }

    @Test
    @DisplayName("编排异常时降级并提示不要编造")
    void shouldDegradeWhenOrchestratorFails() {
        AiRouteContext route = newRoute();
        when(orchestrator.execute(any(AiRouteContext.class), any(AiExpertCandidate.class)))
            .thenThrow(new IllegalStateException("down"));
        Map<String, Object> input = Map.of("action", "route", "expert_agent_key", "finance_agent");
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, input, routeContext(route))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("专家调用失败：down"));
        assertTrue(route.degraded);
    }

    @Test
    @DisplayName("指定专家时忽略模型入参并按 pinned 执行")
    void shouldRunPinnedExpert() {
        AiRouteContext route = newRoute();
        route.pinnedExpertId = 9L;
        when(orchestrator.executePinned(any(AiRouteContext.class))).thenReturn("【专家已直接回答用户】");
        ToolResultBlock result =
            tool.callAsync(param(RouteDecisionTool.TOOL_NAME, Map.of("action", "self"), routeContext(route))).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("专家已直接回答用户"));
        assertEquals(AiRouteRun.ACTION_PINNED, route.action);
        verify(orchestrator, never()).execute(any(), any());
    }
}