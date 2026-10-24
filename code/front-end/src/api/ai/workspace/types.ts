/**
 * AI 工作区文件类型定义（REQ-AI-08）
 */

/** 工作区上下文运行期配置 */
export interface WorkspaceConfig {
  /** 是否启用工作区上下文注入 */
  enabled: boolean;
  /** 附加上下文文件清单（工作区相对路径） */
  additionalContextFiles: string[];
}

/** 工作区文件 */
export interface WorkspaceFile {
  /** 工作区相对路径 */
  path: string;
  /** 文件名 */
  name: string;
  /** 文件类型（AGENTS 人格与行为约定 / EXTRA 附加上下文文件） */
  type: string;
  /** 是否存在共享默认（工作区根） */
  hasShared: boolean;
  /** 共享默认字符数 */
  sharedChars: number;
  /** 是否存在用户覆盖（用户目录） */
  hasUser: boolean;
  /** 用户覆盖字符数 */
  userChars: number;
  /** 生效来源（USER 用户覆盖生效 / SHARED 共享默认生效 / NONE 两处均无内容） */
  effective: string;
}

/** 工作区文件内容 */
export interface WorkspaceContent {
  /** 工作区相对路径 */
  path: string;
  /** 层来源（SHARED 共享默认 / USER 用户覆盖） */
  scope: string;
  /** 文件正文 */
  content: string;
}

/** 工作区文件写入参数 */
export interface WorkspaceContentForm {
  /** 智能体主键 */
  agentId?: string | number;
  /** 用户主键 */
  userId?: string | number;
  /** 工作区相对路径 */
  path: string;
  /** 写入层（SHARED / USER） */
  scope: string;
  /** 文件正文 */
  content: string;
}