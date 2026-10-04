package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 智能体与子智能体关联对象 ai_agent_subagent（REQ-AI-04）
 *
 * <p>物理删除（保存时先删后插），故不设逻辑删除标志。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_subagent")
public class AiAgentSubagent extends BaseEntity {

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
     * 子智能体主键（ai_subagent.id）
     */
    private Long subagentId;

    /**
     * 备注
     */
    private String remark;

}