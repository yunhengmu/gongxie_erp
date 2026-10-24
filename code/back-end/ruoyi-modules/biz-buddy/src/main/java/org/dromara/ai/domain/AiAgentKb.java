package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 智能体与知识库关联对象 ai_agent_kb
 *
 * <p>保存时「先删后插」，故不设 del_flag，物理删除。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_kb")
public class AiAgentKb extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 知识库主键（ai_knowledge_base.id）
     */
    private Long kbId;

    /**
     * 备注
     */
    private String remark;

}
