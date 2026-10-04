package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话级资源集视图对象（REQ-AI-32）：供任务工作台渲染标签（chip）。
 *
 * @author BizBuddy
 */
@Data
public class AiSessionResourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 资源类型（scenario / tool / skill / mcp_tool）
     */
    private String resourceType;

    /**
     * 资源主键
     */
    private Long resourceId;

    /**
     * 稳定键（场景包编码 / 工具编码 / 技能名 / MCP 远端工具名）
     */
    private String resourceKey;

    /**
     * 展示名（场景名 / 工具名 / 技能名 / MCP 工具名）
     */
    private String displayName;

    /**
     * 副标签（场景包编码 / 工具编码 / Server 名；用于悬浮说明）
     */
    private String subLabel;

    /**
     * 工具类型（0 只读 / 1 写入；仅 resourceType=tool 时有值）
     */
    private String toolType;

}