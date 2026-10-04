package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 场景包工具绑定业务对象（含授权决策）
 *
 * @author BizBuddy
 */
@Data
public class AiScenarioPackToolBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具编码（ai_tool.tool_code）
     */
    @NotBlank(message = "工具编码不能为空")
    private String toolCode;

    /**
     * 授权决策（0允许 1需人工确认 2禁止），留空按「需人工确认」
     */
    private String decision;

}