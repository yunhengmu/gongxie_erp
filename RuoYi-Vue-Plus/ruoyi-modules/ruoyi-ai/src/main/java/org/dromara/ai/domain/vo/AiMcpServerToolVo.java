package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MCP 出口候选工具视图对象（REQ-AI-15）。
 *
 * @author BizBuddy
 */
@Data
public class AiMcpServerToolVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具主键
     */
    private Long id;

    /**
     * 工具编码（即 MCP 出口上的工具名）
     */
    private String toolCode;

    /**
     * 工具显示名
     */
    private String toolName;

    /**
     * 用途说明（即 MCP 工具 description）
     */
    private String description;

    /**
     * 工具类型（0只读 1写入）
     */
    private String toolType;

    /**
     * 是否已对外暴露
     */
    private boolean exposed;

    /**
     * 代码侧实现是否已登记（未登记的工具即便勾选也无法执行）
     */
    private boolean registered;

}