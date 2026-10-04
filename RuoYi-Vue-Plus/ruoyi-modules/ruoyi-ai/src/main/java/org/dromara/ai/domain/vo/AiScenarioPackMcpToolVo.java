package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 场景包内外部 MCP 工具视图对象（含授权决策）
 *
 * @author BizBuddy
 */
@Data
public class AiScenarioPackMcpToolVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * MCP 连接主键
     */
    private Long mcpClientId;

    /**
     * Server 名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String serverName;

    /**
     * 远端自声明的工具名
     */
    private String toolName;

    /**
     * 授权决策（0允许 1需人工确认 2禁止）
     */
    private String decision;

}