package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiSkill;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 技能业务对象 ai_skill
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSkill.class, reverseConvertGenerate = false)
public class AiSkillBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 技能标识（唯一，即 AgentScope 的 skillId）
     */
    @NotBlank(message = "技能标识不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "技能标识不能超过 100 个字符", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^[a-z][a-z0-9_-]{1,60}$",
        message = "技能标识只能包含小写字母、数字、下划线或短横线，且以字母开头（2~61 位）",
        groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 技能用途说明（供模型判断是否加载）
     */
    @NotBlank(message = "技能用途说明不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 1000, message = "技能用途说明不能超过 1000 个字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * SKILL.md 正文（Markdown，可为空）；frontmatter 由后端按 name / description 自动拼装
     */
    private String skillBody;

    /**
     * 归属部门（生效范围为「本部门」时生效）
     */
    private Long ownerDeptId;

    /**
     * 生效范围（0公开 1本部门 2仅本人）
     */
    private String visibleScope;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 参考资料明细（保存时先删后插）
     */
    private List<AiSkillResourceBo> resources;

    /**
     * 列表查询用：按标识或说明模糊匹配
     */
    private String keyword;

}
