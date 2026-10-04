package org.dromara.ai.service.impl;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.agent.AiToolFactory;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.vo.AiMcpServerToolVo;
import org.dromara.ai.domain.vo.AiMcpServerVo;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mcp.config.McpServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AiMcpServerServiceImpl} 单元测试：出口状态回显、候选工具清单、暴露开关切换与出口清单同步。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MCP 出口 Service")
class AiMcpServerServiceImplTest {

    @Mock
    private AiToolMapper toolMapper;

    @Mock
    private AiToolFactory toolFactory;

    @Mock
    private IAiKnowledgeBaseService knowledgeBaseService;

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;

    @Mock
    private ObjectProvider<McpStatelessSyncServer> mcpServerProvider;

    @Mock
    private ObjectProvider<McpServerProperties> mcpServerPropertiesProvider;

    private AiMcpServerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiMcpServerServiceImpl(toolMapper, toolFactory, knowledgeBaseService,
            runtimeConfigStore, mcpServerProvider, mcpServerPropertiesProvider);
    }

    private AiTool tool(Long id, String code, String exposed, String implName) {
        AiTool tool = new AiTool();
        tool.setId(id);
        tool.setToolCode(code);
        tool.setToolName("n");
        tool.setDescription("d");
        tool.setToolType(AiTool.TYPE_READ_ONLY);
        tool.setStatus(AiTool.STATUS_ENABLED);
        tool.setMcpExposed(exposed);
        tool.setImplName(implName);
        return tool;
    }

    private McpSchema.Tool liveTool(String name) {
        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn(name);
        return tool;
    }

    @Test
    @DisplayName("出口状态：回显端点、口令与实际挂载工具")
    void shouldQueryServer() {
        AiMcpServerConfigBo config = new AiMcpServerConfigBo();
        config.setAuthEnabled(true);
        config.setToken("tk");
        when(runtimeConfigStore.mcpServer()).thenReturn(config);
        McpServerProperties properties = new McpServerProperties();
        properties.setEndpoint("/mcp");
        when(mcpServerPropertiesProvider.getIfAvailable()).thenReturn(properties);
        McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
        McpSchema.Tool liveB = liveTool("b");
        McpSchema.Tool liveA = liveTool("a");
        when(server.listTools()).thenReturn(List.of(liveB, liveA));
        when(mcpServerProvider.getIfAvailable()).thenReturn(server);

        AiMcpServerVo vo = service.queryServer();

        assertEquals("/mcp", vo.getEndpoint());
        assertTrue(vo.isAuthEnabled());
        assertEquals("tk", vo.getToken());
        assertEquals(List.of("a", "b"), vo.getLiveTools());
    }

    @Test
    @DisplayName("出口状态：服务端与属性缺失时降级")
    void shouldQueryServerWithoutBeans() {
        AiMcpServerConfigBo config = new AiMcpServerConfigBo();
        when(runtimeConfigStore.mcpServer()).thenReturn(config);
        when(mcpServerPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(mcpServerProvider.getIfAvailable()).thenReturn(null);

        AiMcpServerVo vo = service.queryServer();

        assertNull(vo.getEndpoint());
        assertTrue(vo.getLiveTools().isEmpty());
    }

    @Test
    @DisplayName("候选工具清单：映射暴露与登记状态")
    void shouldQueryTools() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_YES, "impl")));
        when(toolFactory.isSupported("impl")).thenReturn(true);

        List<AiMcpServerToolVo> result = service.queryTools();

        assertEquals(1, result.size());
        assertTrue(result.get(0).isExposed());
        assertTrue(result.get(0).isRegistered());
    }

    @Test
    @DisplayName("更新暴露：不支持的编码抛异常")
    void shouldRejectInvalidExposedCodes() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_NO, "impl")));

        assertThrows(ServiceException.class, () -> service.updateExposed(List.of("bad")));
    }

    @Test
    @DisplayName("更新暴露：状态变化时更新行")
    void shouldUpdateExposedState() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_NO, "impl")));
        when(mcpServerProvider.getIfAvailable()).thenReturn(null);

        service.updateExposed(List.of("c"));

        verify(toolMapper).updateById(any(AiTool.class));
    }

    @Test
    @DisplayName("更新暴露：状态未变化不更新行")
    void shouldSkipExposedUpdateWhenUnchanged() {
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_YES, "impl")));
        when(mcpServerProvider.getIfAvailable()).thenReturn(null);

        service.updateExposed(List.of("c"));

        verify(toolMapper, never()).updateById(any(AiTool.class));
    }

    @Test
    @DisplayName("同步出口：服务端缺失时直接跳过")
    void shouldSkipReconcileWithoutServer() {
        when(mcpServerProvider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(service::reconcile);
        assertDoesNotThrow(service::initExposedTools);
    }

    @Test
    @DisplayName("同步出口：新增暴露工具并挂载")
    void shouldReconcileAddTool() {
        McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
        when(mcpServerProvider.getIfAvailable()).thenReturn(server);
        when(server.listTools()).thenReturn(List.of());
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_YES, "impl")));
        when(toolFactory.isSupported("impl")).thenReturn(true);

        service.reconcile();

        verify(server).addTool(any());
    }

    @Test
    @DisplayName("同步出口：实现未登记的工具跳过挂载")
    void shouldSkipUnsupportedToolOnReconcile() {
        McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
        when(mcpServerProvider.getIfAvailable()).thenReturn(server);
        when(server.listTools()).thenReturn(List.of());
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_YES, "unknown")));
        when(toolFactory.isSupported("unknown")).thenReturn(false);

        service.reconcile();

        verify(server, never()).addTool(any());
    }

    @Test
    @DisplayName("同步出口：已挂载且定义未变则幂等跳过")
    void shouldReconcileIdempotently() {
        McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);
        when(mcpServerProvider.getIfAvailable()).thenReturn(server);
        McpSchema.Tool liveC = liveTool("c");
        when(server.listTools()).thenReturn(List.of(liveC));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(1L, "c", AiTool.EXPOSED_YES, "impl")));
        when(toolFactory.isSupported("impl")).thenReturn(true);

        service.reconcile();
        service.reconcile();

        verify(server, times(1)).addTool(any());
        verify(server, times(1)).removeTool("c");
    }

}