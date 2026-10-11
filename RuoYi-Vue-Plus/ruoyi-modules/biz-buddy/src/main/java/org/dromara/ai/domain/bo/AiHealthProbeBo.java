package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 平台健康自检探测请求（REQ-AI-35）。
 *
 * <p>自检（{@code GET /ai/health/check}）只做本地判定、绝不外呼；需要真实连接的项由本对象驱动
 * {@code POST /ai/health/probe} 单独探测。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiHealthProbeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 探测目标：model（模型连通性）/ milvus（向量库连接）/ mcp-client（外部 MCP 连接）
     */
    @NotBlank(message = "探测目标不能为空")
    private String target;

    /**
     * 探测对象主键（model / mcp-client 必填；milvus 忽略）
     */
    private Long id;

}
