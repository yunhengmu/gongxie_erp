package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 智能体工具信任决策视图对象（「记住该工具」沉淀的免确认决策）
 *
 * @author BizBuddy
 */
@Data
public class AiAgentToolTrustVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具编码（业务工具编码或外部 MCP 工具名）
     */
    private String toolCode;

    /**
     * 记住该工具的用户主键
     */
    private Long userId;

    /**
     * 记住该工具的用户昵称
     */
    private String userName;

    /**
     * 信任决策（0允许 1需确认 2禁止）
     */
    private String decision;

    /**
     * 决策来源（remember / manual）
     */
    private String source;

    /**
     * 形成时间
     */
    private LocalDateTime createTime;

}
