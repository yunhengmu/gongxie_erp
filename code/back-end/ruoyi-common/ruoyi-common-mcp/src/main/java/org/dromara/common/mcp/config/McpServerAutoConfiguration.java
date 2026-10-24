package org.dromara.common.mcp.config;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * MCP 出口自动配置（官方 MCP Java SDK 自建）。
 * <p>
 * 说明：原先由 spring-ai 的 MCP Server starter 承担，但 spring-ai 2.0.1 与 AgentScope 2.0.3 在
 * victools / MCP SDK / networknt 三处依赖版本上互斥（详见 docs/agentscope/A09 Phase 0 报告），
 * 故改为直接使用官方 SDK：{@link HttpServletStatelessServerTransport} 本身即 Servlet，
 * 通过 {@link ServletRegistrationBean} 挂载到配置的端点。
 * <p>
 * <b>工具扩展点</b>：业务模块只需向容器注册 {@link McpStatelessServerFeatures.SyncToolSpecification}
 * （或 {@link McpStatelessServerFeatures.SyncResourceSpecification}）Bean，即会自动暴露为 MCP 能力。
 *
 * @author Lion Li
 */
@AutoConfiguration
@EnableConfigurationProperties(McpServerProperties.class)
@ConditionalOnProperty(prefix = "mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerAutoConfiguration {

    /**
     * 无状态 HTTP 传输（Servlet），无会话管理，适合同端口短连接调用。
     *
     * @param properties MCP 出口配置
     * @return 无状态传输
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpServletStatelessServerTransport mcpServerTransport(McpServerProperties properties) {
        return HttpServletStatelessServerTransport.builder()
            .messageEndpoint(properties.getEndpoint())
            .build();
    }

    /**
     * 把传输 Servlet 注册到配置的端点路径。
     *
     * @param transport  MCP 传输
     * @param properties MCP 出口配置
     * @return Servlet 注册
     */
    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServerServlet(
        HttpServletStatelessServerTransport transport, McpServerProperties properties) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
            new ServletRegistrationBean<>(transport, properties.getEndpoint());
        registration.setName("mcpServerServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * 组装 MCP 服务端，工具与资源全部来自容器中的 Specification Bean。
     *
     * @param transport  MCP 传输
     * @param properties MCP 出口配置
     * @param tools      工具扩展点
     * @param resources  资源扩展点
     * @return MCP 无状态同步服务端
     */
    @Bean
    @ConditionalOnMissingBean
    public McpStatelessSyncServer mcpStatelessSyncServer(
        HttpServletStatelessServerTransport transport,
        McpServerProperties properties,
        ObjectProvider<McpStatelessServerFeatures.SyncToolSpecification> tools,
        ObjectProvider<McpStatelessServerFeatures.SyncResourceSpecification> resources) {
        McpServer.StatelessSyncSpecification specification = McpServer.sync(transport)
            .serverInfo(properties.getName(), properties.getVersion());
        if (StringUtils.isNotBlank(properties.getInstructions())) {
            specification.instructions(properties.getInstructions());
        }
        // 必须**显式声明**工具 / 资源能力：官方 SDK 仅在对应 Specification 列表**非空**时才自动设置能力
        // （见 McpStatelessServerFeatures：`!Utils.isEmpty(tools) ? ToolCapabilities : null`）。
        // 而本项目的业务工具是**运行期**由 AiMcpServerServiceImpl#addTool 挂载的（无启动期 Bean），
        // 若此处不显式开启，能力为 null，运行期 addTool 会抛
        // 「Server must be configured with tool capabilities」，导致出口一个工具都挂不上。
        // （历史上由 ruoyi-demo 的演示 Bean 兜底，移除该模块后该缺陷才暴露。）
        specification.capabilities(new McpSchema.ServerCapabilities(
            null,
            null,
            new McpSchema.ServerCapabilities.LoggingCapabilities(),
            null,
            new McpSchema.ServerCapabilities.ResourceCapabilities(false, false),
            new McpSchema.ServerCapabilities.ToolCapabilities(false)));
        // 启动期由 Bean 贡献的工具 / 资源（业务侧为运行期挂载，此处通常为空集）
        specification.tools(tools.orderedStream().toList());
        specification.resources(resources.orderedStream().toList());
        return specification.build();
    }
}