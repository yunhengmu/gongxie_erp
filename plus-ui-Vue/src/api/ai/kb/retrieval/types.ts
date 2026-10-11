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
  /** 章节路径（各级标题用 > 连接，如 第2章 > 2.3 考勤） */
  headingPath?: string;
  /** 切片起始页码（PDF 等分页文档） */
  pageNo?: number;
  /** 切片在文档纯文本中的起始字符下标（含） */
  charStart?: number;
  /** 切片在文档纯文本中的结束字符下标（不含） */
  charEnd?: number;
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