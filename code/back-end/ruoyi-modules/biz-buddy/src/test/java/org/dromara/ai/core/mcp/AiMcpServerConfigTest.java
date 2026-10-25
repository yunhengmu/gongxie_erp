package org.dromara.ai.core.mcp;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.common.mcp.config.McpServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiMcpServerConfig} 单元测试：过滤器注册端点与顺序。
 */
@DisplayName("MCP 出口过滤器注册配置")
class AiMcpServerConfigTest {

    private final AiMcpServerConfig config = new AiMcpServerConfig();

    @SuppressWarnings("unchecked")
    private ObjectProvider<McpServerProperties> providerOf(McpServerProperties value) {
        ObjectProvider<McpServerProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    @DisplayName("出口属性缺失时使用默认端点 /mcp")
    void shouldUseDefaultEndpointWhenPropertiesMissing() {
        AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

        FilterRegistrationBean<AiMcpAuthFilter> registration =
            config.aiMcpAuthFilter(providerOf(null), store);

        assertNotNull(registration.getFilter());
        assertEquals("aiMcpAuthFilter", registration.getFilterName());
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, registration.getOrder());
        assertEquals(2, registration.getUrlPatterns().size());
        assertTrue(registration.getUrlPatterns().contains("/mcp"));
        assertTrue(registration.getUrlPatterns().contains("/mcp/*"));
    }

    @Test
    @DisplayName("按出口属性端点注册过滤器")
    void shouldUseConfiguredEndpoint() {
        AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);
        McpServerProperties properties = new McpServerProperties();
        properties.setEndpoint("/custom-mcp");

        FilterRegistrationBean<AiMcpAuthFilter> registration =
            config.aiMcpAuthFilter(providerOf(properties), store);

        assertTrue(registration.getUrlPatterns().contains("/custom-mcp"));
        assertTrue(registration.getUrlPatterns().contains("/custom-mcp/*"));
    }

}