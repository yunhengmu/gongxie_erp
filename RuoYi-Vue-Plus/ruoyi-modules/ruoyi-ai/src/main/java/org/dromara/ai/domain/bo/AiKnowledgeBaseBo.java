package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 知识库业务对象 ai_knowledge_base
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiKnowledgeBase.class, reverseConvertGenerate = false)
public class AiKnowledgeBaseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 知识库名称
     */
    @NotBlank(message = "知识库名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String kbName;

    /**
     * 知识库编码（唯一）
     */
    @NotBlank(message = "知识库编码不能为空", groups = {AddGroup.class, EditGroup.class})
    private String kbCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 向量模型（ai_model_config.id，model_type=embedding）
     */
    @NotNull(message = "向量模型不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long embeddingModelId;

    /**
     * 向量维度：留空时取向量模型配置的维度
     */
    private Integer dimension;

    /**
     * 切片长度（字符）
     */
    @Min(value = 50, message = "切片长度不能小于 50", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 8000, message = "切片长度不能大于 8000", groups = {AddGroup.class, EditGroup.class})
    private Integer chunkSize;

    /**
     * 切片重叠长度（字符）
     */
    @Min(value = 0, message = "切片重叠长度不能小于 0", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 2000, message = "切片重叠长度不能大于 2000", groups = {AddGroup.class, EditGroup.class})
    private Integer chunkOverlap;

    /**
     * 默认召回条数
     */
    @Min(value = 1, message = "召回条数不能小于 1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 50, message = "召回条数不能大于 50", groups = {AddGroup.class, EditGroup.class})
    private Integer retrievalTopK;

    /**
     * 默认相似度阈值
     */
    @DecimalMin(value = "0.000", message = "相似度阈值不能小于 0", groups = {AddGroup.class, EditGroup.class})
    @DecimalMax(value = "1.000", message = "相似度阈值不能大于 1", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal similarityThreshold;

    /**
     * 状态（0正常 1停用）
     */
    private String status;

    /**
     * 归属部门（visibleScope=1 本部门时生效）；留空时由后端取当前用户部门
     */
    private Long ownerDeptId;

    /**
     * 生效范围（0公开 1本部门 2仅本人）；留空按「公开」处理
     */
    @Pattern(regexp = "^[012]?$", message = "生效范围只能是 0 公开 / 1 本部门 / 2 仅本人",
        groups = {AddGroup.class, EditGroup.class})
    private String visibleScope;

    /**
     * 备注
     */
    private String remark;

}