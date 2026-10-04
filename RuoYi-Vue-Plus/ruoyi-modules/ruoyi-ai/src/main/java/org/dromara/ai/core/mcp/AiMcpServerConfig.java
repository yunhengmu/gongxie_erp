package org.dromara.ai.core.mcp;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.common.mcp.config.McpServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * MCP 出口访问口令校验的注册配置（REQ-AI-15）。
 *
 * <p>把 {@link AiMcpAuthFilter} 挂到 MCP 出口端点上（与 {@code mcp.server.endpoint} 保持一致，
 * 默认 {@code /mcp}）。出口端点是 Servlet（不经 DispatcherServlet），故 Sa-Token 的登录拦截
 * 对其无效，口令校验必须由 Servlet Filter 承担。</p>
 *
 * @author BizBuddy
 */
@Configuration
public class AiMcpServerConfig {

    /**
     * 出口端点缺省路径（与 {@code McpServerProperties#getEndpoint} 默认值一致）
     */
    private static final String DEFAULT_ENDPOINT = "/mcp";

    /**
     * 注册 MCP 出口口令校验过滤器。
     *
     * @param propertiesProvider MCP 出口属性（出口未启用时不存在，取缺省端点）
     * @param runtimeConfigStore 运行期配置（口令开关与口令）
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<AiMcpAuthFilter> aiMcpAuthFilter(
        ObjectProvider<McpServerProperties> propertiesProvider, AiRuntimeConfigStore runtimeConfigStore) {
        McpServerProperties properties = propertiesProvider.getIfAvailable();
        String endpoint = properties == null ? DEFAULT_ENDPOINT : properties.getEndpoint();
        FilterRegistrationBean<AiMcpAuthFilter> registration =
            new FilterRegistrationBean<>(new AiMcpAuthFilter(runtimeConfigStore));
        registration.addUrlPatterns(endpoint, endpoint + "/*");
        registration.setName("aiMcpAuthFilter");
        // 先于业务过滤器执行：未通过口令校验的请求不应触达出口 Servlet
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

}