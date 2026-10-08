package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * MCP 出口状态视图对象（REQ-AI-15）。
 *
 * @author BizBuddy
 */
@Data
public class AiMcpServerVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 出口端点路径（与 {@code mcp.server.endpoint} 一致）
     */
    private String endpoint;

    /**
     * 是否启用访问口令校验
     */
    private boolean authEnabled;

    /**
     * 访问口令（需回显给管理员交付给外部调用方）
     */
    private String token;

    /**
     * 当前出口**实际**挂载的工具名（含平台内置的演示工具，可用于核对热更新是否生效）
     */
    private List<String> liveTools;

}