package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.ai.domain.AiTool;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 业务工具注册业务对象 ai_tool
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiTool.class, reverseConvertGenerate = false)
public class AiToolBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 工具编码（唯一）
     */
    @NotBlank(message = "工具编码不能为空", groups = {AddGroup.class, EditGroup.class})
    private String toolCode;

    /**
     * 工具显示名
     */
    @NotBlank(message = "工具名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String toolName;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 工具类型（0只读 1写入）
     */
    private String toolType;

    /**
     * 参数定义（JSON Schema）
     */
    private String paramsSchema;

    /**
     * 代码侧实现标识（AiToolFactory 中的 key）
     */
    private String implName;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 列表查询用：按编码或名称模糊匹配
     */
    private String keyword;

    /**
     * 标签主键集合。**保存时**表示覆盖式设置该工具的标签（空集合表示清除全部标签，
     * 传 {@code null} 表示不修改标签）；**列表查询时**表示按标签筛选，语义为**任一命中（OR）**。
     */
    private List<Long> tagIds;

    /**
     * 列表查询用：只看**未打任何标签**的工具（便于发现未分类工具）
     */
    private Boolean noTag;

}
