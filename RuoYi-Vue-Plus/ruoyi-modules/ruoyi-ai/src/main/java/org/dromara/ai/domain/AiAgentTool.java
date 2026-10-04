package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 智能体与业务工具关联对象 ai_agent_tool
 *
 * <p>保存时「先删后插」，故不设 del_flag，物理删除。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_tool")
public class AiAgentTool extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具授权决策：允许直接执行
     */
    public static final String DECISION_ALLOW = "0";

    /**
     * 工具授权决策：需人工确认（HITL）
     */
    public static final String DECISION_ASK = "1";

    /**
     * 工具授权决策：禁止调用
     */
    public static final String DECISION_DENY = "2";

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
     * 工具编码（ai_tool.tool_code）
     */
    private String toolCode;

    /**
     * 工具授权决策（0允许 1需人工确认 2禁止）
     */
    private String decision;

    /**
     * 备注
     */
    private String remark;

}
