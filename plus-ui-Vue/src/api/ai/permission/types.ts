/**
 * AI 工具权限决策类型定义
 */

export interface PermissionDecisionVO {
  /** 主键 */
  id: string | number;
  /** 所属会话主键 */
  sessionId?: string | number;
  /** 会话标题 */
  sessionTitle?: string;
  /** 所属助手消息主键 */
  messageId?: string | number;
  /** 智能体主键 */
  agentId?: string | number;
  /** 智能体名称 */
  agentName?: string;
  /** 触发用户主键 */
  userId?: string | number;
  /** 触发用户昵称 */
  userName?: string;
  /** 工具编码 */
  toolCode?: string;
  /** 工具名称 */
  toolName?: string;
  /** 决策（0允许 1需确认 2拒绝） */
  decision?: string;
  /** 决策来源（agent_tool / remember / global_mode / fallback） */
  decisionSource?: string;
  /** 命中的规则名 */
  matchedRule?: string;
  /** 决策说明 */
  reason?: string;
  /** 工具入参 JSON */
  toolInput?: string;
  /** 人工确认结果（0拒绝 1确认） */
  confirmed?: string;
  /** 确认作用范围（once / remember） */
  confirmSource?: string;
  /** 工具执行耗时（毫秒） */
  durationMs?: number;
  /** 创建时间 */
  createTime?: string;
}

export interface PermissionDecisionQuery extends PageQuery {
  /** 所属会话主键 */
  sessionId?: string | number;
  /** 工具编码 */
  toolCode?: string;
  /** 决策 */
  decision?: string;
  /** 人工确认结果 */
  confirmed?: string;
  /** 触发用户主键 */
  userId?: string | number;
  /** 智能体主键 */
  agentId?: string | number;
  /** 创建时间起（含） */
  beginTime?: string;
  /** 创建时间止（含） */
  endTime?: string;
}