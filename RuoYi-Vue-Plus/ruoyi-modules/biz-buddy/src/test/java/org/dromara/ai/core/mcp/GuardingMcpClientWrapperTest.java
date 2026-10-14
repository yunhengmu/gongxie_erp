package org.dromara.ai.core.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyAction;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardingMcpClientWrapper} 单元测试：回参脱敏、失败结果与开关关闭时的透传。
 */
@DisplayName("MCP 回参安全守卫装饰器")
class GuardingMcpClientWrapperTest {

    private final AiSafetyGuard safetyGuard = mock(AiSafetyGuard.class);

    private static McpSchema.CallToolResult textResult(String text, boolean error) {
        return McpSchema.CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent(null, text, Map.of("k", "v"))))
            .isError(error)
            .build();
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    @DisplayName("命中时逐块脱敏，且保留 annotations / meta")
    void shouldMaskTextContent() {
        McpSchema.CallToolResult raw = textResult("联系电话 13812345678", false);
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.callTool(anyString(), anyMap())).thenReturn(Mono.just(raw));
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard("联系电话 13812345678", SafetyScene.TOOL_RESULT))
            .thenReturn(new SafetyDecision(SafetyAction.MASK, "联系电话 138****5678", true, List.of("phone")));

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);
        McpSchema.CallToolResult guarded = wrapper.callTool("t", Map.of()).block();

        assertEquals("联系电话 138****5678", textOf(guarded));
        assertEquals("v", ((McpSchema.TextContent) guarded.content().get(0)).meta().get("k"));
    }

    @Test
    @DisplayName("判定 BLOCK 时以统一拒答文案替代原文")
    void shouldReplaceWithRefusalWhenBlocked() {
        McpSchema.CallToolResult raw = textResult("违规词", false);
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.callTool(anyString(), anyMap())).thenReturn(Mono.just(raw));
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard("违规词", SafetyScene.TOOL_RESULT))
            .thenReturn(SafetyDecision.block(List.of("违规词")));

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);
        McpSchema.CallToolResult guarded = wrapper.callTool("t", Map.of()).block();

        assertEquals(AiSafetyGuard.REFUSAL_TEXT, textOf(guarded));
    }

    @Test
    @DisplayName("带 meta 的 callTool 同样过守卫")
    void shouldGuardCallToolWithMeta() {
        McpSchema.CallToolResult raw = textResult("联系电话 13812345678", false);
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.callTool(anyString(), anyMap(), anyMap())).thenReturn(Mono.just(raw));
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard(anyString(), eq(SafetyScene.TOOL_RESULT)))
            .thenReturn(new SafetyDecision(SafetyAction.MASK, "157****1234", true, List.of("phone")));

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);
        McpSchema.CallToolResult guarded = wrapper.callTool("t", Map.of(), Map.of("trace", "1")).block();

        assertEquals("157****1234", textOf(guarded));
    }

    @Test
    @DisplayName("失败结果（isError）原样放行")
    void shouldPassThroughErrorResult() {
        McpSchema.CallToolResult raw = textResult("参数校验失败：13812345678", true);
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.callTool(anyString(), anyMap())).thenReturn(Mono.just(raw));
        when(safetyGuard.anyActive()).thenReturn(true);

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);

        assertSame(raw, wrapper.callTool("t", Map.of()).block());
        verify(safetyGuard, never()).guard(anyString(), eq(SafetyScene.TOOL_RESULT));
    }

    @Test
    @DisplayName("未命中时返回原回参对象")
    void shouldReturnSameResultWhenNoHit() {
        McpSchema.CallToolResult raw = textResult("正常内容", false);
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.callTool(anyString(), anyMap())).thenReturn(Mono.just(raw));
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard(anyString(), eq(SafetyScene.TOOL_RESULT)))
            .thenReturn(SafetyDecision.pass("正常内容"));

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);

        assertSame(raw, wrapper.callTool("t", Map.of()).block());
    }

    @Test
    @DisplayName("安全能力全关时走快速路径，不改写也不调用守卫")
    void shouldSkipWhenGuardInactive() {
        McpSchema.CallToolResult raw = textResult("联系电话 13812345678", false);
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.callTool(anyString(), anyMap())).thenReturn(Mono.just(raw));
        when(safetyGuard.anyActive()).thenReturn(false);

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);

        assertSame(raw, wrapper.callTool("t", Map.of()).block());
        verify(safetyGuard, never()).guard(anyString(), eq(SafetyScene.TOOL_RESULT));
    }

    @Test
    @DisplayName("工具清单与生命周期方法原样透传（不改工具定义、权限面不受影响）")
    void shouldDelegateToolsAndLifecycle() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        List<McpSchema.Tool> tools = List.of(McpSchema.Tool.builder().name("t").build());
        when(delegate.listTools()).thenReturn(Mono.just(tools));
        when(delegate.getCachedTool("t")).thenReturn(tools.get(0));
        when(delegate.initialize()).thenReturn(Mono.empty());
        when(delegate.isInitialized()).thenReturn(true);

        GuardingMcpClientWrapper wrapper = new GuardingMcpClientWrapper(delegate, safetyGuard);

        assertEquals("srv", wrapper.getName());
        assertSame(tools, wrapper.listTools().block());
        assertSame(tools.get(0), wrapper.getCachedTool("t"));
        assertTrue(wrapper.isInitialized());
        assertNull(wrapper.initialize().block());
        wrapper.close();
        verify(delegate).close();
    }

}
