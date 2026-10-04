package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 业务工具标签批量操作业务对象（批量打标签 / 批量移除标签）
 *
 * <p>两个动作都以「勾选的工具 × 勾选的标签」为作用面：
 * 打标签为**追加**语义（不动工具已有标签），移除标签只删除指定的关联。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiToolTagBatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标工具主键集合
     */
    @NotEmpty(message = "请先勾选要操作的工具")
    private List<Long> toolIds;

    /**
     * 标签主键集合
     */
    @NotEmpty(message = "请先选择标签")
    private List<Long> tagIds;

}
