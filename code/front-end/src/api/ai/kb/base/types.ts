/**
 * AI 知识库类型定义
 */

export interface KnowledgeBaseVO {
  /** 主键 */
  id: string | number;
  /** 知识库名称 */
  kbName: string;
  /** 知识库编码 */
  kbCode: string;
  /** 描述 */
  description?: string;
  /** 向量模型主键 */
  embeddingModelId: string | number;
  /** 向量模型名称 */
  embeddingModelName?: string;
  /** 向量集合名称 */
  collectionName?: string;
  /** 向量维度 */
  dimension?: number;
  /** 切片长度（字符） */
  chunkSize?: number;
  /** 切片重叠长度（字符） */
  chunkOverlap?: number;
  /** 默认召回条数 */
  retrievalTopK?: number;
  /** 默认相似度阈值 */
  similarityThreshold?: number;
  /** 是否启用检索重排（0否 1是） */
  rerankEnabled?: string;
  /** 重排模型主键（为空表示不重排） */
  rerankModelId?: string | number;
  /** 重排模型名称 */
  rerankModelName?: string;
  /** 检索方式（vector 向量 / hybrid 向量+BM25） */
  retrievalMode?: string;
  /** BM25 索引状态（none / building / ready / failed） */
  bm25IndexStatus?: string;
  /** 状态（0正常 1停用） */
  status: string;
  /** 归属部门（visibleScope=1 本部门时生效） */
  ownerDeptId?: string | number;
  /** 生效范围（0公开 1本部门 2仅本人） */
  visibleScope?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface KnowledgeBaseForm extends BaseEntity {
  /** 主键 */
  id?: string | number;
  /** 知识库名称 */
  kbName?: string;
  /** 知识库编码 */
  kbCode?: string;
  /** 描述 */
  description?: string;
  /** 向量模型主键 */
  embeddingModelId?: string | number;
  /** 向量维度 */
  dimension?: number;
  /** 切片长度（字符） */
  chunkSize?: number;
  /** 切片重叠长度（字符） */
  chunkOverlap?: number;
  /** 默认召回条数 */
  retrievalTopK?: number;
  /** 默认相似度阈值 */
  similarityThreshold?: number;
  /** 是否启用检索重排（0否 1是） */
  rerankEnabled?: string;
  /** 重排模型主键（启用重排时必填） */
  rerankModelId?: string | number;
  /** 检索方式（vector 向量 / hybrid 向量+BM25） */
  retrievalMode?: string;
  /** 状态（0正常 1停用） */
  status?: string;
  /** 归属部门（visibleScope=1 本部门时生效） */
  ownerDeptId?: string | number;
  /** 生效范围（0公开 1本部门 2仅本人） */
  visibleScope?: string;
  /** 备注 */
  remark?: string;
}

export interface KnowledgeBaseQuery extends PageQuery {
  /** 知识库名称 */
  kbName?: string;
  /** 知识库编码 */
  kbCode?: string;
  /** 状态 */
  status?: string;
}