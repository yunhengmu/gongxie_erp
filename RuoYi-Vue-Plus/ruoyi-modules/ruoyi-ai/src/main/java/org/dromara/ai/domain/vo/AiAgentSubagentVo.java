package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiAgentSubagent;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 智能体与子智能体关联视图对象 ai_agent_subagent
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiAgentSubagent.class)
public class AiAgentSubagentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 子智能体主键
     */
    private Long subagentId;

}