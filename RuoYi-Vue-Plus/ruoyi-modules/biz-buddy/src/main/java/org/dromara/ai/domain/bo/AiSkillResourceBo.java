package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 技能参考资料业务对象 ai_skill_resource
 *
 * @author BizBuddy
 */
@Data
public class AiSkillResourceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 相对路径（如 references/faq.md）
     */
    @NotBlank(message = "参考资料路径不能为空")
    @Size(max = 500, message = "参考资料路径不能超过 500 个字符")
    private String resourcePath;

    /**
     * 文件内容
     */
    private String resourceContent;

}
