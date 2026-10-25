package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiKnowledgeBase;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 知识库视图对象 ai_knowledge_base
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiKnowledgeBase.class)
public class AiKnowledgeBaseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 知识库名称
     */
    private String kbName;

    /**
     * 知识库编码
     */
    private String kbCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 向量模型主键
     */
    private Long embeddingModelId;

    /**
     * 向量模型名称（联表补充，便于列表展示）
     */
    private String embeddingModelName;

    /**
     * 是否启用检索重排（0否 1是）
     */
    private String rerankEnabled;

    /**
     * 重排模型主键（为空表示不重排）
     */
    private Long rerankModelId;

    /**
     * 重排模型名称（联表补充，便于列表展示）
     */
    private String rerankModelName;

    /**
     * 检索方式（vector 向量 / hybrid 向量+BM25）
     */
    private String retrievalMode;

    /**
     * BM25 索引状态（none / building / ready / failed）
     */
    private String bm25IndexStatus;

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
     * 状态（0正常 1停用）
     */
    private String status;

    /**
     * 归属部门（visibleScope=1 本部门时生效）
     */
    private Long ownerDeptId;

    /**
     * 生效范围（0公开 1本部门 2仅本人）
     */
    private String visibleScope;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}