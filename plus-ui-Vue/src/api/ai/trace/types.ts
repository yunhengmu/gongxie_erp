/**
 * AI 链路追踪类型定义（任务执行链路 / 上下文清单 / 状态迁移）
 */

export interface TraceVO {
  /** 主键 */
  id?: string | number;
  /** 所属会话主键 */
  sessionId?: string | number;
  /** 归属消息主键（实时对话一问一答） */
  messageId?: string | number | null;
  /** 智能体主键 */
  agentId?: string | number;
  /** 智能体名称 */
  agentName?: string;
  /** 智能体编码快照 */
  agentCode?: string;
  /** 来源（chat 实时对话 / task 定时任务） */
  source?: string;
  /** 归属用户 ID */
  userId?: string | number | null;
  /** 归属用户昵称 */
  userName?: string;
  /** 会话标题 */
  sessionTitle?: string;
  /** 模型主键 */
  modelId?: string | number | null;
  /** 执行时主对话模型标识（快照） */
  modelCode?: string;
  /** 模型提供方 */
  modelProvider?: string;
  /** 压缩摘要模型（未配置为空） */
  summaryModelCode?: string;
  /** 记忆抽取与合并模型（未配置为空） */
  memoryModelCode?: string;
  /** 子智能体默认模型（未配置为空） */
  subagentModelCode?: string;
  /** 状态（running / pending_confirm / success / failed / aborted） */
  status?: string;
  /** 耗时（毫秒） */
  durationMs?: number | null;
  /** 工具调用次数 */
  toolCallCount?: number | null;
  /** Prompt Token 数 */
  promptTokens?: number | null;
  /** Completion Token 数 */
  completionTokens?: number | null;
  /** 总 Token 数 */
  totalTokens?: number | null;
  /** 失败原因 */
  errorMsg?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
  /** 仅详情接口返回：上下文清单 */
  context?: Record<string, any>;
  /** 仅详情接口返回：状态迁移时间线 */
  transitions?: Array<{ at?: string; state?: string; detail?: string }>;
}

export interface TraceQuery extends PageQuery {
  /** 会话主键（供从会话管理页深链直达） */
  sessionId?: string | number;
  /** 消息主键 */
  messageId?: string | number;
  /** 智能体主键 */
  agentId?: string | number;
  /** 来源（chat 实时对话 / task 定时任务） */
  source?: string;
  /** 状态（running / pending_confirm / success / failed / aborted） */
  status?: string;
  /** 模型标识（模糊匹配） */
  modelCode?: string;
  /** 时间起（YYYY-MM-DD HH:mm:ss） */
  beginTime?: string;
  /** 时间止（YYYY-MM-DD HH:mm:ss） */
  endTime?: string;
}