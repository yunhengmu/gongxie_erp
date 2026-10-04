package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 场景包 × 业务工具关联对象 ai_scenario_pack_tool
 *
 * <p>每个工具各带一篇三档授权决策（{@code 0} 允许直接执行 / {@code 1} 需人工确认（框架原生 HITL）/
 * {@code 2} 禁止调用），与 {@code ai_agent_tool.decision} 同口径。装配时**同一工具以包内决策优先**
 * （即包内决策覆盖智能体自身的绑定决策）。</p>
 *
 * <p>保存时「先删后插」，故不设 {@code del_flag}，物理删除。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_scenario_pack_tool")
public class AiScenarioPackTool extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 授权决策：允许直接执行
     */
    public static final String DECISION_ALLOW = "0";

    /**
     * 授权决策：需人工确认（HITL）
     */
    public static final String DECISION_ASK = "1";

    /**
     * 授权决策：禁止调用
     */
    public static final String DECISION_DENY = "2";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 场景包主键（ai_scenario_pack.id）
     */
    private Long packId;

    /**
     * 工具编码（ai_tool.tool_code）
     */
    private String toolCode;

    /**
     * 授权决策（0允许 1需人工确认 2禁止）
     */
    private String decision;

    /**
     * 备注
     */
    private String remark;

}