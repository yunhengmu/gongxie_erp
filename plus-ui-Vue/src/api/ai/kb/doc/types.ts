/**
 * AI 知识库文档与切片类型定义
 */

export interface DocumentVO {
  /** 主键 */
  id: string | number;
  /** 所属知识库 */
  kbId: string | number;
  /** 文档名称 */
  docName: string;
  /** 文件后缀（小写，不含点） */
  fileSuffix?: string;
  /** OSS 文件 ID */
  ossId?: string | number;
  /** 文件访问地址 */
  fileUrl?: string;
  /** 文件大小（字节） */
  fileSize?: number;
  /** 解析后字符数 */
  charCount?: number;
  /** 切片数量 */
  chunkCount?: number;
  /** 解析状态（0待解析 1解析中 2已解析 3解析失败） */
  parseStatus: string;
  /** 解析失败原因 */
  errorMsg?: string;
  /** 向量化状态（0待向量化 1向量化中 2已向量化 3向量化失败） */
  vectorStatus?: string;
  /** 向量化失败原因 */
  vectorErrorMsg?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface DocumentForm extends BaseEntity {
  /** 主键 */
  id?: string | number;
  /** 所属知识库 */
  kbId?: string | number;
  /** 文档名称 */
  docName?: string;
  /** 文件后缀 */
  fileSuffix?: string;
  /** OSS 文件 ID */
  ossId?: string | number;
  /** 文件大小 */
  fileSize?: number;
  /** 备注 */
  remark?: string;
}

export interface DocumentQuery extends PageQuery {
  /** 所属知识库 */
  kbId?: string | number;
  /** 文档名称 */
  docName?: string;
  /** 解析状态 */
  parseStatus?: string;
  /** 向量化状态 */
  vectorStatus?: string;
}

export interface ChunkVO {
  /** 主键 */
  id: string | number;
  /** 所属知识库 */
  kbId: string | number;
  /** 所属文档 */
  docId: string | number;
  /** 切片序号（从 0 开始） */
  chunkIndex: number;
  /** 切片正文 */
  content: string;
  /** 字符数 */
  charCount?: number;
  /** 向量库主键 */
  vectorId?: string;
  /** 章节路径（各级标题用 > 连接，如 第2章 > 2.3 考勤） */
  headingPath?: string;
  /** 切片起始页码（PDF 等分页文档） */
  pageNo?: number;
  /** 切片在文档纯文本中的起始字符下标（含） */
  charStart?: number;
  /** 切片在文档纯文本中的结束字符下标（不含） */
  charEnd?: number;
  /** 创建时间 */
  createTime?: string;
}

/** 切片定位结果（点击回答「参考来源」时按文档 + 序号现查） */
export interface ChunkContextVO {
  /** 命中切片；文档已重新解析导致序号不存在时为 null */
  target?: ChunkVO | null;
  /** 命中切片之前的相邻切片（按序号升序） */
  before: ChunkVO[];
  /** 命中切片之后的相邻切片（按序号升序） */
  after: ChunkVO[];
}