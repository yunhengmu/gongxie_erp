/**
 * AI 审计类型定义（会话与消息审计 / 工具调用明细）
 */

export interface MessageAuditVO {
  /** 主键 */
  id: string | number;
  /** 所属会话主键 */
  sessionId: string | number;
  /** 会话标题 */
  sessionTitle?: string;
  /** 智能体主键 */
  agentId?: string | number;
  /** 智能体名称 */
  agentName?: string;
  /** 归属用户 ID */
  userId?: string | number;
  /** 归属用户昵称 */
  userName?: string;
  /** 角色（user / assistant / system） */
  role: 'user' | 'assistant' | 'system';
  /** 消息正文 */
  content?: string;
  /** AgentScope replyId */
  replyId?: string;
  /** 状态（0正常 1失败） */
  status?: string;
  /** 失败原因 */
  errorMsg?: string;
  /** 工具调用次数 */
  toolCallCount?: number;
  /** Prompt Token 数 */
  promptTokens?: number;
  /** Completion Token 数 */
  completionTokens?: number;
  /** 总 Token 数 */
  totalTokens?: number;
  /** 耗时（毫秒） */
  durationMs?: number;
  /** 创建时间 */
  createTime?: string;
}

export interface MessageAuditQuery extends PageQuery {
  /** 正文模糊关键字 */
  keyword?: string;
  /** 角色（user / assistant） */
  role?: string;
  /** 状态（0正常 1失败） */
  status?: string;
  /** 会话主键 */
  sessionId?: string | number;
  /** 会话标题（模糊匹配） */
  sessionTitle?: string;
  /** 智能体主键 */
  agentId?: string | number;
  /** 归属用户 ID */
  userId?: string | number;
  /** 时间起（ISO，如 2026-09-26T00:00:00） */
  beginTime?: string;
  /** 时间止（ISO） */
  endTime?: string;
}

export interface ToolCallAuditVO {
  /** 主键 */
  id: string | number;
  /** 所属会话主键 */
  sessionId: string | number;
  /** 会话标题 */
  sessionTitle?: string;
  /** 发起该工具调用的智能体主键（入口轮＝会话智能体，专家执行＝专家；历史行为空） */
  agentId?: string | number;
  /** 发起该工具调用的智能体名称（按 agentId 回填，为空回落会话智能体） */
  agentName?: string;
  /** 所属助手消息主键 */
  messageId?: string | number;
  /** 触发用户 ID */
  userId?: string | number;
  /** 触发用户昵称 */
  userName?: string;
  /** AgentScope toolCallId */
  toolCallId?: string;
  /** 工具名称 */
  toolName: string;
  /** 入参 JSON */
  toolInput?: string;
  /** 结果摘要 */
  toolOutput?: string;
  /** 结果状态（SUCCESS / ERROR / INTERRUPTED / DENIED） */
  state?: string;
  /** 执行耗时（毫秒） */
  durationMs?: number;
  /** 创建时间 */
  createTime?: string;
}

export interface ToolCallAuditQuery extends PageQuery {
  /** 关键字（工具名 / 入参 / 出参模糊匹配） */
  keyword?: string;
  /** 工具名称 */
  toolName?: string;
  /** 结果状态（SUCCESS / ERROR / INTERRUPTED / DENIED） */
  state?: string;
  /** 会话主键 */
  sessionId?: string | number;
  /** 发起该工具调用的智能体主键（含已停用；选项见 /ai/audit/agent/options） */
  agentId?: string | number;
  /** 触发用户 ID */
  userId?: string | number;
  /** 归属用户 ID */
  ownerUserId?: string | number;
  /** 时间起（ISO，如 2026-09-26T00:00:00） */
  beginTime?: string;
  /** 时间止（ISO） */
  endTime?: string;
}

export interface ToolCallStatVO {
  /** 工具名称 */
  toolName: string;
  /** 调用总次数 */
  totalCount?: number;
  /** 成功次数 */
  successCount?: number;
  /** 失败次数 */
  failCount?: number;
  /** 平均耗时（毫秒） */
  avgDurationMs?: number;
  /** 失败率（百分比数值，如 33.33） */
  failRate?: number;
}
