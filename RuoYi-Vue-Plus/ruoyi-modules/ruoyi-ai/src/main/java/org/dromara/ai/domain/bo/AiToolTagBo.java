package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiToolTag;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 业务工具标签业务对象 ai_tool_tag
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiToolTag.class, reverseConvertGenerate = false)
public class AiToolTagBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 标签名称（唯一）
     */
    @NotBlank(message = "标签名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = AiToolTag.TAG_NAME_MAX_LENGTH, message = "标签名称不能超过 50 个字符",
        groups = {AddGroup.class, EditGroup.class})
    private String tagName;

    /**
     * 备注（用途说明）
     */
    @Size(max = 500, message = "备注不能超过 500 个字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 列表查询用：按标签名称模糊匹配
     */
    private String keyword;

}