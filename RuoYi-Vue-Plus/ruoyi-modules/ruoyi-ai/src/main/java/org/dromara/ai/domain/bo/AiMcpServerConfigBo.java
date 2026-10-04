package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MCP 出口访问配置（REQ-AI-15）。
 *
 * <p>平台级运行期配置，保存在 Redis（键 {@code bizbuddy:ai:runtime:mcp-server}）；
 * 保存后立即生效，重启后仍保留。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiMcpServerConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用访问口令校验（关闭时 /mcp 完全开放，仅建议内网调试时临时关闭）
     */
    private boolean authEnabled = true;

    /**
     * 访问口令（对外交付给调用方；留空表示未配置，此时开启校验会拒绝所有请求）
     */
    private String token;

}