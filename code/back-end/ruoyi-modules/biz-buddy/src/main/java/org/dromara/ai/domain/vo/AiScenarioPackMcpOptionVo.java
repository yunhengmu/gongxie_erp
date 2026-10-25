package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 场景包内 MCP 工具选项视图对象（供场景包管理页勾选外部 MCP 工具）
 *
 * @author BizBuddy
 */
@Data
public class AiScenarioPackMcpOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * MCP 连接主键
     */
    private Long mcpClientId;

    /**
     * Server 名称
     */
    private String serverName;

    /**
     * 该连接的工具白名单（即 ai_mcp_client.enable_tools；为空表示未限定，但此时无可选项，
     * 需先在 MCP 连接配置页填写白名单）
     */
    @TableField(exist = false)
    private List<String> tools;

}