package org.dromara.common.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 自建出口配置。
 * <p>
 * 原 `spring.ai.mcp.server.*` 配置已废止（spring-ai 与 AgentScope 依赖互斥，详见 docs/agentscope/A09），
 * 改为使用官方 MCP Java SDK 自建出口后由此属性类承接。
 *
 * @author Lion Li
 */
@Data
@ConfigurationProperties(prefix = "mcp.server")
public class McpServerProperties {

    /**
     * 是否启用 MCP 出口。
     */
    private boolean enabled = true;

    /**
     * MCP 端点路径（与 ruoyi-admin 共用端口）。
     */
    private String endpoint = "/mcp";

    /**
     * 服务名，默认取应用名。
     */
    private String name = "RuoYi-Vue-Plus";

    /**
     * 服务版本。
     */
    private String version = "1.0.0";

    /**
     * 可选的服务说明，会随 initialize 返回给客户端。
     */
    private String instructions;

}