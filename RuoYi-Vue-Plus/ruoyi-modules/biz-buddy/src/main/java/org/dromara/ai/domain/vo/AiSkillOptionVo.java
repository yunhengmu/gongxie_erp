package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSkill;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 技能选项视图对象（供智能体定义页选择绑定技能）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSkill.class)
public class AiSkillOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 技能标识
     */
    private String name;

    /**
     * 技能用途说明
     */
    private String description;

}
