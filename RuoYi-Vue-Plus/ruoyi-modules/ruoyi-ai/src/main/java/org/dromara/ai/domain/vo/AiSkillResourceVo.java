package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSkillResource;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 技能参考资料视图对象 ai_skill_resource
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSkillResource.class)
public class AiSkillResourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属技能主键
     */
    private Long id;

    /**
     * 相对路径（如 references/faq.md）
     */
    private String resourcePath;

    /**
     * 文件内容
     */
    private String resourceContent;

}
