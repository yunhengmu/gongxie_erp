/**
 * AI 会话管理类型定义（会话管理页）
 */

export interface SessionVO {
  /** 会话主键（即 AgentScope 的 sessionId） */
  id: string | number;
  /** 会话标题 */
  sessionTitle?: string;
  /** 所属智能体主键 */
  agentId: string | number;
  /** 智能体编码快照 */
  agentCode?: string;
  /** 智能体名称 */
  agentName?: string;
  /** 归属用户 ID */
  userId?: string | number;
  /** 归属用户昵称 */
  userName?: string;
  /** 归属部门 ID */
  deptId?: string | number;
  /** 归属部门名称 */
  deptName?: string;
  /** 消息条数 */
  messageCount?: number;
  /** 状态（0进行中 1已结束） */
  status?: string;
  /** 置顶标志（0普通 1置顶） */
  topFlag?: string;
  /** 会话来源（chat 手动对话 / task 定时任务） */
  source?: string;
  /** 最后活跃时间 */
  lastActiveTime?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface SessionQuery extends PageQuery {
  /** 会话标题（模糊匹配） */
  sessionTitle?: string;
  /** 会话主键（精确匹配，供从定时任务执行记录直达会话） */
  sessionId?: string | number;
  /** 会话来源（chat 手动对话 / task 定时任务） */
  source?: string;
  /** 所属智能体主键 */
  agentId?: string | number;
  /** 归属用户 ID */
  userId?: string | number;
  /** 状态（0进行中 1已结束） */
  status?: string;
  /** 仅看我的会话 */
  mine?: boolean;
  /** 最后活跃时间起（ISO，如 2026-09-26T00:00:00） */
  beginTime?: string;
  /** 最后活跃时间止（ISO） */
  endTime?: string;
}

export interface SessionMessageVO {
  /** 主键 */
  id: string | number;
  /** 所属会话主键 */
  sessionId: string | number;
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
  /** 本轮工具调用次数 */
  toolCallCount?: number;
  /** Token 数 */
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  /** 本轮耗时（毫秒） */
  durationMs?: number;
  /** 创建时间 */
  createTime?: string;
}

export interface ToolCallLogVO {
  /** 主键 */
  id: string | number;
  /** 所属会话主键 */
  sessionId: string | number;
  /** 所属助手消息主键 */
  messageId?: string | number;
  /** AgentScope toolCallId */
  toolCallId?: string;
  /** 工具名称 */
  toolName: string;
  /** 入参 JSON */
  toolInput?: string;
  /** 结果摘要 */
  toolOutput?: string;
  /** 结果状态（SUCCESS / ERROR / ...） */
  state?: string;
  /** 工具执行耗时（毫秒） */
  durationMs?: number;
  /** 创建时间 */
  createTime?: string;
}
