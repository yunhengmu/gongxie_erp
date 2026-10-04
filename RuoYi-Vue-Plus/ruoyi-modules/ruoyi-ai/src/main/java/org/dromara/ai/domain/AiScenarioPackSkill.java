package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 场景包 × 技能关联对象 ai_scenario_pack_skill
 *
 * <p>包内技能**叠加**到智能体既有技能绑定之上；技能无授权决策（技能本身无闸门），
 * 且仍受技能生效范围（{@code ai_skill.visible_scope}）按提问用户过滤。</p>
 *
 * <p>保存时「先删后插」，故不设 {@code del_flag}，物理删除。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_scenario_pack_skill")
public class AiScenarioPackSkill extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

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
     * 技能主键（ai_skill.id）
     */
    private Long skillId;

    /**
     * 备注
     */
    private String remark;

}