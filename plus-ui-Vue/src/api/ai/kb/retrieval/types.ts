/**
 * AI 知识库检索测试类型定义
 */

export interface RetrievalHitVO {
  /** 切片序号 */
  chunkIndex?: number;
  /** 相似度得分（COSINE，越大越相关） */
  score?: number;
  /** 切片正文 */
  content: string;
  /** 来源文档主键 */
  docId?: string | number;
  /** 来源文档名称 */
  docName?: string;
}

export interface RetrievalQuery {
  /** 知识库主键 */
  kbId?: string | number;
  /** 查询文本 */
  query?: string;
  /** 召回条数，为空取知识库配置 */
  topK?: number;
  /** 相似度阈值，为空取知识库配置 */
  threshold?: number;
}