package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 场景包 × 外部 MCP 工具关联对象 ai_scenario_pack_mcp_tool
 *
 * <p>按「连接 + 远端自声明的原始工具名」勾选：只有包内勾选项才会装配进工具箱，**未选包的会话
 * 不接入任何 MCP 工具**。连接是否启用、以及 {@code ai_mcp_client.enable_tools} 白名单仍是前置闸门，
 * 包内勾选只能是其子集。注册进工具箱时工具名会被归一化重命名（见 {@code McpToolNameNormalizer}）。</p>
 *
 * <p>保存时「先删后插」，故不设 {@code del_flag}，物理删除。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_scenario_pack_mcp_tool")
public class AiScenarioPackMcpTool extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 场景包主键（ai_scenario_pack.id）
     */
    private Long packId;

    /**
     * MCP 连接主键（ai_mcp_client.id）
     */
    private Long mcpClientId;

    /**
     * 远端自声明的工具名（即 ai_mcp_client.enable_tools 的白名单口径）
     */
    private String toolName;

    /**
     * 授权决策（0允许 1需人工确认 2禁止）
     */
    private String decision;

    /**
     * 备注
     */
    private String remark;

}