package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话级资源集单项（REQ-AI-32）：批量增 / 删的基本单元。
 *
 * @author BizBuddy
 */
@Data
public class AiSessionResourceItemBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型（scenario / tool / skill / mcp_tool）
     */
    private String resourceType;

    /**
     * 资源主键（MCP 工具时为 MCP 连接主键）
     */
    private Long resourceId;

    /**
     * 稳定键：MCP 工具必填（远端工具名）；其余类型可空（服务端按主键回填编码 / 名称）
     */
    private String resourceKey;

}