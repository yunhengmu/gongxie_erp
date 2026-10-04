package org.dromara.ai.core.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NormalizingMcpClientWrapper} 单元测试：工具改名、调用名还原与透传。
 */
@DisplayName("MCP 工具名归一化装饰器")
class NormalizingMcpClientWrapperTest {

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder()
            .name(name)
            .description("desc-" + name)
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), Boolean.FALSE,
                Map.of(), Map.of()))
            .build();
    }

    @Test
    @DisplayName("listTools 将已登记工具改名，未登记的保持不变")
    void shouldRenameRegisteredTools() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.listTools()).thenReturn(Mono.just(List.of(tool("a.b"), tool("plain"))));

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of("a.b", "a_b"));
        List<McpSchema.Tool> tools = wrapper.listTools().block();

        assertEquals("srv", wrapper.getName());
        assertEquals(2, tools.size());
        assertEquals("a_b", tools.get(0).name());
        assertEquals("desc-a.b", tools.get(0).description());
        assertEquals("plain", tools.get(1).name());
    }

    @Test
    @DisplayName("别名与原名相同的工具不重建")
    void shouldKeepToolWhenAliasEqualsName() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        McpSchema.Tool original = tool("same");
        when(delegate.listTools()).thenReturn(Mono.just(List.of(original)));

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of("same", "same"));

        assertSame(original, wrapper.listTools().block().get(0));
    }

    @Test
    @DisplayName("工具清单为空时返回空列表")
    void shouldReturnEmptyForEmptyTools() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.listTools()).thenReturn(Mono.just(List.of()));

        NormalizingMcpClientWrapper wrapper = new NormalizingMcpClientWrapper(delegate, Map.of());
        assertTrue(wrapper.listTools().block().isEmpty());
    }

    @Test
    @DisplayName("callTool 把归一化名还原为远端原始名")
    void shouldRestoreRemoteNameOnCall() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        McpSchema.CallToolResult result = mock(McpSchema.CallToolResult.class);
        when(delegate.callTool(anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(Mono.just(result));

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of("a.b", "a_b"));
        assertSame(result, wrapper.callTool("a_b", Map.of("k", "v")).block());
        verify(delegate).callTool(org.mockito.ArgumentMatchers.eq("a.b"), org.mockito.ArgumentMatchers.eq(Map.of("k", "v")));
    }

    @Test
    @DisplayName("带 meta 的 callTool 同样还原工具名")
    void shouldRestoreRemoteNameOnCallWithMeta() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        McpSchema.CallToolResult result = mock(McpSchema.CallToolResult.class);
        when(delegate.callTool(anyString(), org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(Mono.just(result));

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of("a.b", "a_b"));
        Map<String, Object> meta = Map.of("trace", "1");
        assertSame(result, wrapper.callTool("a_b", Map.of(), meta).block());
        verify(delegate).callTool("a.b", Map.of(), meta);
    }

    @Test
    @DisplayName("未登记的工具名原样透传")
    void shouldPassThroughUnmappedName() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        McpSchema.CallToolResult result = mock(McpSchema.CallToolResult.class);
        when(delegate.callTool(anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(Mono.just(result));

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of("a.b", "a_b"));
        wrapper.callTool("other", Map.of()).block();
        verify(delegate).callTool("other", Map.of());
    }

    @Test
    @DisplayName("getCachedTool 别名还原为远端名查询")
    void shouldGetCachedToolByRemoteName() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        McpSchema.Tool remote = tool("a.b");
        McpSchema.Tool plain = tool("plain");
        when(delegate.getCachedTool("a.b")).thenReturn(remote);
        when(delegate.getCachedTool("plain")).thenReturn(plain);

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of("a.b", "a_b"));
        assertSame(remote, wrapper.getCachedTool("a_b"));
        assertSame(plain, wrapper.getCachedTool("plain"));
    }

    @Test
    @DisplayName("生命周期方法透传委托")
    void shouldDelegateLifecycle() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.initialize()).thenReturn(Mono.empty());
        when(delegate.isInitialized()).thenReturn(true);

        NormalizingMcpClientWrapper wrapper =
            new NormalizingMcpClientWrapper(delegate, Map.of());
        assertNull(wrapper.initialize().block());
        assertTrue(wrapper.isInitialized());
        wrapper.close();

        verify(delegate).initialize();
        verify(delegate).close();
    }

}