package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiMcpClient;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 外部 MCP Server 连接配置视图对象 ai_mcp_client（REQ-AI-16）。
 *
 * <p>请求头（{@code headers_json}，可能含凭据）**不出接口**，仅以
 * {@link #headersConfigured} 标识是否已配置。</p>
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiMcpClient.class)
public class AiMcpClientVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * Server 名称
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
     * 工具白名单（逗号分隔；为空表示放行全部工具）
     */
    private String enableTools;

    /**
     * 单次请求超时（毫秒）
     */
    private Integer timeoutMs;

    /**
     * 初始化超时（毫秒）
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
     * 是否已配置请求头（附加字段：明文/密文均不回显）
     */
    @TableField(exist = false)
    private boolean headersConfigured;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}