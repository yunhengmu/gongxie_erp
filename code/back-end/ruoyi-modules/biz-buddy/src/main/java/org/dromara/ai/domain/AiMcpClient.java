package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 外部 MCP Server 连接配置对象 ai_mcp_client（REQ-AI-16）。
 *
 * <p>为**平台级**配置：启用后全部智能体装配时都会接入该 Server 的工具，
 * 暴露面由 {@code enable_tools} 工具白名单收敛。仅支持 {@code sse} / {@code http}
 * （streamable）传输——不放开 {@code stdio}，避免在应用进程内拉起任意子进程。</p>
 *
 * <p>{@code headers_json} 可承载 {@code Authorization} 等凭据，以 AES 密文落库，
 * 接口与页面均不回显明文。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_mcp_client")
public class AiMcpClient extends BaseEntity {

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    /**
     * 传输方式：SSE（有状态）
     */
    public static final String TRANSPORT_SSE = "sse";

    /**
     * 传输方式：HTTP（streamable，无状态）
     */
    public static final String TRANSPORT_HTTP = "http";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * Server 名称（唯一，即 ToolsConfig.mcpServers 的键与 MCP 客户端名）
     */
    private String serverName;

    /**
     * 传输方式（sse / http）
     */
    private String transport;

    /**
     * Server 地址
     */
    private String url;

    /**
     * 请求头 JSON（AES 密文）
     */
    private String headersJson;

    /**
     * 工具白名单（逗号分隔；为空表示放行该 Server 全部工具）
     */
    private String enableTools;

    /**
     * 单次请求超时（毫秒），为空取框架默认
     */
    private Integer timeoutMs;

    /**
     * 初始化超时（毫秒），为空取框架默认
     */
    private Integer initTimeoutMs;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}