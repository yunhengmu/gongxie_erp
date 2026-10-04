package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 场景包业务对象 ai_scenario_pack
 *
 * <p>包内三类资源以集合形式收发：业务工具（含授权决策）、外部 MCP 工具（含授权决策）、技能主键。
 * 服务层保存时对三张关联表「先删后插」。</p>
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiScenarioPack.class, reverseConvertGenerate = false)
public class AiScenarioPackBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 场景包编码（唯一，创建后不可修改）
     */
    @NotBlank(message = "场景包编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "场景包编码不能超过 64 个字符", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^[a-z][a-z0-9_-]{1,63}$",
        message = "场景包编码只能包含小写字母、数字、下划线或短横线，且以字母开头（2~64 位）",
        groups = {AddGroup.class, EditGroup.class})
    private String packCode;

    /**
     * 场景包名称（如「财务场景包」「人力场景包」）
     */
    @NotBlank(message = "场景包名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "场景包名称不能超过 100 个字符", groups = {AddGroup.class, EditGroup.class})
    private String packName;

    /**
     * 用途说明（创建任务时供用户判断该选哪个包）
     */
    @Size(max = 500, message = "用途说明不能超过 500 个字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 生效范围（0 公开 / 1 本部门 / 2 仅本人）
     */
    private String visibleScope;

    /**
     * 归属部门（visible_scope=1 时比较该列）
     */
    private Long ownerDeptId;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 包内业务工具（含授权决策）
     */
    private List<AiScenarioPackToolBo> toolBindings;

    /**
     * 包内外部 MCP 工具（含授权决策）
     */
    private List<AiScenarioPackMcpToolBo> mcpToolBindings;

    /**
     * 包内技能主键集合
     */
    private List<Long> skillIds;

    /**
     * 列表查询用：按编码 / 名称 / 用途说明模糊匹配
     */
    private String keyword;

}