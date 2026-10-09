package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * MCP 出口暴露清单保存请求（REQ-AI-15）。
 *
 * @author BizBuddy
 */
@Data
public class AiMcpExposedBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 需要对外暴露的工具编码集合（仅启用中的只读工具可暴露；未列出的工具将被取消暴露）
     */
    private List<String> toolCodes;

}