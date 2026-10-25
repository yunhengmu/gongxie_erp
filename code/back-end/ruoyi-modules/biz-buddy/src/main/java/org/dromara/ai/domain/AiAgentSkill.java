package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 智能体与技能关联对象 ai_agent_skill
 *
 * <p>物理删除（保存时先删后插），故不设逻辑删除标志。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_skill")
public class AiAgentSkill extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 智能体主键（ai_agent.id）
     */
    private Long agentId;

    /**
     * 技能主键（ai_skill.id）
     */
    private Long skillId;

    /**
     * 备注
     */
    private String remark;

}
