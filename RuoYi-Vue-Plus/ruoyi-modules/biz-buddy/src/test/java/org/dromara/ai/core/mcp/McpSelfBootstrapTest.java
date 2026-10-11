package org.dromara.ai.core.mcp;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import io.github.linpeilie.Converter;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpSelfBootstrap} 单元测试：幂等插入与凭据构造。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 自演示引导")
class McpSelfBootstrapTest {

    @Mock
    private AiMcpClientMapper mcpClientMapper;

    @Mock
    private AiSecretManager secretManager;

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 注入最小内存容器：供 {@code IdGeneratorUtil} / {@code LambdaQueryWrapper} 等静态初始化使用，不连真实外部依赖。
     */
    @BeforeAll
    static void initStaticContext() {
        AiTestSupport.registerTableInfo();
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("identifierGenerator", mock(IdentifierGenerator.class));
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.getBeanFactory().registerSingleton("jsonMapper", JsonMapper.builder().build());
        context.refresh();
        new SpringUtils().setApplicationContext(context);
    }

    @Test
    @DisplayName("不存在同名连接时插入：白名单仅系统信息工具、Authorization 头为密文")
    void shouldInsertWhenAbsent() {
        when(mcpClientMapper.selectCount(any())).thenReturn(0L);
        AiMcpServerConfigBo config = new AiMcpServerConfigBo();
        config.setToken("BizBuddy");
        when(runtimeConfigStore.mcpServer()).thenReturn(config);
        when(secretManager.encrypt("{\"Authorization\":\"Bearer BizBuddy\"}")).thenReturn("cipher");

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(123L);
            new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);
        }

        ArgumentCaptor<AiMcpClient> captor = ArgumentCaptor.forClass(AiMcpClient.class);
        verify(mcpClientMapper).insert(captor.capture());
        AiMcpClient inserted = captor.getValue();
        assertEquals(Long.valueOf(123L), inserted.getId());
        assertEquals("platform_system_info", inserted.getServerName());
        assertEquals(AiMcpClient.TRANSPORT_HTTP, inserted.getTransport());
        assertEquals("http://localhost:8080/mcp", inserted.getUrl());
        assertEquals("get_system_info", inserted.getEnableTools());
        assertEquals("cipher", inserted.getHeadersJson());
        assertEquals(AiMcpClient.STATUS_ENABLED, inserted.getStatus());
    }

    @Test
    @DisplayName("已存在同名连接时不再插入（幂等）")
    void shouldSkipWhenPresent() {
        when(mcpClientMapper.selectCount(any())).thenReturn(1L);

        new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);

        verify(mcpClientMapper, never()).insert(any(AiMcpClient.class));
    }

    @Test
    @DisplayName("出口口令为空时不写 Authorization 头")
    void shouldOmitHeaderWhenTokenBlank() {
        when(mcpClientMapper.selectCount(any())).thenReturn(0L);
        AiMcpServerConfigBo config = new AiMcpServerConfigBo();
        config.setToken("");
        when(runtimeConfigStore.mcpServer()).thenReturn(config);

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(456L);
            new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);
        }

        ArgumentCaptor<AiMcpClient> captor = ArgumentCaptor.forClass(AiMcpClient.class);
        verify(mcpClientMapper).insert(captor.capture());
        assertNull(captor.getValue().getHeadersJson());
    }

}