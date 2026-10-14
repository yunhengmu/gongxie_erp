package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * AI 知识库对象 ai_knowledge_base
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_knowledge_base")
public class AiKnowledgeBase extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 知识库名称
     */
    private String kbName;

    /**
     * 知识库编码（唯一，用于命名向量集合）
     */
    private String kbCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 向量模型（ai_model_config.id）
     */
    private Long embeddingModelId;

    /**
     * 向量集合名称
     */
    private String collectionName;

    /**
     * 向量维度
     */
    private Integer dimension;

    /**
     * 切片长度（字符）
     */
    private Integer chunkSize;

    /**
     * 切片重叠长度（字符）
     */
    private Integer chunkOverlap;

    /**
     * 默认召回条数
     */
    private Integer retrievalTopK;

    /**
     * 默认相似度阈值
     */
    private BigDecimal similarityThreshold;

    /**
     * 是否启用检索重排（0否 1是，非空默认 0）：开启后检索走「稠密召回 → 重排 → 融合 → 截断 topK」
     */
    private String rerankEnabled;

    /**
     * 重排模型（ai_model_config.id，model_type=rerank）：为空表示不重排
     * <p>关闭重排时需把本列清空，故用 ALWAYS——否则 updateById 会跳过 null 字段，库里仍留着旧模型 id。</p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long rerankModelId;

    /**
     * 状态（0正常 1停用）
     */
    private String status;

    /**
     * 归属部门（visibleScope=1 本部门时生效）
     */
    private Long ownerDeptId;

    /**
     * 生效范围（0公开 1本部门 2仅本人），口径与 ai_agent.visible_scope 一致
     */
    private String visibleScope;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}