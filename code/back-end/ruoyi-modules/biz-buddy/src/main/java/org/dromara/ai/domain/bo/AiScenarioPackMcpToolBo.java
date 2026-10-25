package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 场景包 MCP 工具绑定业务对象（含授权决策）
 *
 * @author BizBuddy
 */
@Data
public class AiScenarioPackMcpToolBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * MCP 连接主键（ai_mcp_client.id）
     */
    @NotNull(message = "MCP 连接不能为空")
    private Long mcpClientId;

    /**
     * 远端自声明的工具名（即 ai_mcp_client.enable_tools 的白名单口径）
     */
    @NotBlank(message = "MCP 工具名不能为空")
    private String toolName;

    /**
     * 授权决策（0允许 1需人工确认 2禁止），留空按「需人工确认」
     */
    private String decision;

}