package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 外部 MCP Server 连接配置业务对象 ai_mcp_client（REQ-AI-16）。
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiMcpClient.class, reverseConvertGenerate = false)
public class AiMcpClientBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * Server 名称（唯一）
     */
    @NotBlank(message = "Server 名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String serverName;

    /**
     * 传输方式（sse / http）
     */
    @NotBlank(message = "传输方式不能为空", groups = {AddGroup.class, EditGroup.class})
    private String transport;

    /**
     * Server 地址
     */
    @NotBlank(message = "Server 地址不能为空", groups = {AddGroup.class, EditGroup.class})
    private String url;

    /**
     * 请求头 JSON（明文输入；留空表示保持不变——凭据密文不回显，故不能以空值覆盖）
     */
    private String headersJson;

    /**
     * 工具白名单（逗号分隔；为空表示放行该 Server 全部工具）
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
     * 列表查询用：按 Server 名称或地址模糊匹配
     */
    private String keyword;

}