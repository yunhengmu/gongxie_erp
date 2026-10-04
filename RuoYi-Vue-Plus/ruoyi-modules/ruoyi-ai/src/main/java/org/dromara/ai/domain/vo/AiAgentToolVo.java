package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 智能体工具绑定视图对象（含授权决策）
 *
 * @author BizBuddy
 */
@Data
public class AiAgentToolVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具编码
     */
    private String toolCode;

    /**
     * 授权决策（0允许 1需人工确认 2禁止）
     */
    private String decision;

}