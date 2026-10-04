package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiAgentSkill;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 智能体与技能关联视图对象 ai_agent_skill
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiAgentSkill.class)
public class AiAgentSkillVo implements Serializable {

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
     * 技能主键
     */
    private Long skillId;

}
