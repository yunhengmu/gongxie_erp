package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 敏感词库业务对象 ai_sensitive_word
 *
 * @author BizBuddy
 */
@Data
public class AiSensitiveWordBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 敏感词或正则表达式
     */
    @NotBlank(message = "敏感词不能为空")
    @Size(max = 200, message = "敏感词长度不能超过 200 个字符")
    private String word;

    /**
     * 是否正则：0否（子串匹配） 1是
     */
    @Pattern(regexp = "^[01]$", message = "是否正则只能为 0 或 1")
    private String isRegex;

    /**
     * 分类（可空，供分组管理与统计）
     */
    @Size(max = 50, message = "分类长度不能超过 50 个字符")
    private String category;

    /**
     * 是否启用：1启用 0禁用
     */
    @Pattern(regexp = "^[01]$", message = "启用状态只能为 0 或 1")
    private String enabled;

    /**
     * 备注
     */
    @Size(max = 255, message = "备注长度不能超过 255 个字符")
    private String remark;

}