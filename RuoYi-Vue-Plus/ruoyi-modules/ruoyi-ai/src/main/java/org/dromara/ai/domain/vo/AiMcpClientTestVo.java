package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 外部 MCP Server 连接测试结果（REQ-AI-16）。
 *
 * @author BizBuddy
 */
@Data
public class AiMcpClientTestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否连接成功
     */
    private boolean success;

    /**
     * 结果说明（成功条数或失败原因）
     */
    private String message;

    /**
     * Server 名称
     */
    private String serverName;

    /**
     * 该 Server 对外声明的工具名列表（未成功时为空）
     */
    private List<String> tools;

}