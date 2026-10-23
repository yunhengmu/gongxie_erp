/**
 * AI 长期记忆类型定义（REQ-AI-09）
 */

/** 长期记忆运行期配置 */
export interface MemoryConfig {
  /** 是否启用长期记忆 */
  enabled: boolean;
  /** 记忆管道模式：framework 框架托管 / shadow 影子 / platform 平台托管 */
  pipelineMode: 'framework' | 'shadow' | 'platform';
  /** 抽取时携带的只读上下文消息条数 */
  extractContextMessages: number;
  /** 抽取只读上下文字符上限 */
  extractContextMaxChars: number;
  /** 抽取时已有记忆摘要字符上限 */
  extractKnownMemoryMaxChars: number;
  /** 显式记忆注入 Token 上限 */
  injectDeclaredMaxTokens: number;
  /** 记忆注入 system prompt 的上下文 Token 上限 */
  maxContextTokens: number;
  /** 合并时允许读取的每日流水 Token 上限 */
  consolidationMaxTokens: number;
  /** 两次合并之间的最小间隔（分钟） */
  consolidationMinGapMinutes: number;
  /** 每日流水归档保留天数 */
  dailyFileRetentionDays: number;
  /** 归档目录（memory/archive/）保留天数 */
  archiveRetentionDays: number;
  /** 会话日志保留天数 */
  sessionRetentionDays: number;
  /** 事实抽取触发策略：always / throttled / never */
  flushMode: string;
  /** 事实抽取节流间隔（分钟），仅 throttled 时生效 */
  flushThrottleMinutes: number;
}

/** 记忆文件 */
export interface MemoryFile {
  /** 工作区相对路径 */
  path: string;
  /** 文件名 */
  name: string;
  /** 文件类型（MEMORY 长期记忆 / DAILY 每日流水） */
  type: string;
  /** 日期（每日流水取文件名，长期记忆为空） */
  date?: string;
  /** 字符数 */
  chars: number;
}

/** 记忆文件内容 */
export interface MemoryContent {
  /** 工作区相对路径 */
  path: string;
  /** 文件正文 */
  content: string;
}

/** 记忆文件写入参数 */
export interface MemoryContentForm {
  /** 智能体主键 */
  agentId?: string | number;
  /** 记忆归属用户主键 */
  userId?: string | number;
  /** 工作区相对路径 */
  path: string;
  /** 文件正文 */
  content: string;
}