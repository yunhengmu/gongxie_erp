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

/** 记忆运行健康指标（近 N 天审计聚合，仅运行指标，不含语义召回率） */
export interface MemoryQuality {
  /** 统计窗口（天） */
  days: number;
  /** 抽取执行次数 */
  extractRuns: number;
  /** 其中无产出（NO_REPLY）的次数 */
  extractEmpty: number;
  /** 保留的事实条数 */
  extractKept: number;
  /** 丢弃的 needs_context 条数 */
  needsContext: number;
  /** 丢弃的低置信条数 */
  lowConfidence: number;
  /** 抽取失败次数 */
  extractFailures: number;
  /** 合并执行次数 */
  consolidateRuns: number;
  /** 合并失败次数 */
  consolidateFailures: number;
  /** 最近一次合并耗时（毫秒） */
  lastConsolidateMs: number;
  /** 最近一次合并消耗的 Token */
  lastConsolidateTokens: number;
  /** 最近一次成功抽取时间 */
  lastExtractAt: string;
  /** 最近一次成功合并时间 */
  lastConsolidateAt: string;
}

/** MEMORY.md 历史版本 */
export interface MemoryVersion {
  /** 版本 ID */
  versionId: string;
  /** 创建时间 */
  createdAt: string;
  /** 来源：PRE_MANUAL / MANUAL / PRE_CONSOLIDATE / CONSOLIDATE / PRE_RESTORE / RESTORE 等 */
  source: string;
  /** 操作人 */
  operatorId?: string | number;
  /** 字符数 */
  chars: number;
  /** 当时的合并水位 */
  watermark?: string;
  /** 备注 */
  note?: string;
  /** 被恢复的版本 ID */
  restoredFrom?: string;
  /** 是否为当前版本 */
  current?: boolean;
  /** 版本正文（详情接口返回） */
  content?: string;
}

/** 版本恢复参数 */
export interface MemoryRestoreForm {
  agentId?: string | number;
  userId?: string | number;
  mode: 'CONTENT' | 'CONTENT_AND_WATERMARK';
  note?: string;
}

/** 显式记忆条目（用户明确要求记住的个人偏好） */
export interface MemoryDeclaredEntry {
  /** 条目主键 */
  id: string;
  /** 事实正文 */
  fact: string;
  /** 用户原话证据 */
  evidence?: string;
  /** 创建时间 */
  createdAt: string;
  /** 是否已撤销 */
  revoked: boolean;
}

/** 显式记忆新增参数 */
export interface MemoryDeclaredForm {
  /** 智能体主键 */
  agentId: string | number;
  /** 记忆归属用户主键 */
  userId: string | number;
  /** 事实正文 */
  fact: string;
  /** 用户原话证据 */
  evidence?: string;
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