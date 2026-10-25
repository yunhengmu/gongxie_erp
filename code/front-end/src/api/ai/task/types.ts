/**
 * AI 定时任务类型定义（定时任务页）
 */

export interface TaskVO {
  /** 主键 */
  id: string | number;
  /** 任务名称 */
  taskName?: string;
  /** 运行的智能体主键 */
  agentId?: string | number;
  /** 智能体编码快照 */
  agentCode?: string;
  /** 智能体名称 */
  agentName?: string;
  /** 智能体状态（1 表示已停用，任务将无法执行） */
  agentStatus?: string;
  /** 任务提示词 */
  taskPrompt?: string;
  /** Cron 表达式（Spring 6 位：秒 分 时 日 月 周） */
  cronExpression?: string;
  /** 运行身份用户 */
  runAsUserId?: string | number;
  /** 运行身份用户昵称 */
  runAsUserName?: string;
  /** 运行身份部门 */
  runAsDeptId?: string | number;
  /** 运行身份部门名称 */
  runAsDeptName?: string;
  /** 上次计划执行时间 */
  lastRunTime?: string;
  /** 预计下次执行时间 */
  nextRunTime?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface TaskQuery extends PageQuery {
  /** 任务名称（模糊匹配） */
  keyword?: string;
  /** 智能体主键 */
  agentId?: string | number;
  /** 状态（0启用 1停用） */
  status?: string;
}

export interface TaskForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 任务名称 */
  taskName?: string;
  /** 运行的智能体主键 */
  agentId?: string | number;
  /** 任务提示词 */
  taskPrompt?: string;
  /** Cron 表达式 */
  cronExpression?: string;
  /** 运行身份用户 */
  runAsUserId?: string | number;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
}

export interface TaskRunVO {
  /** 主键 */
  id: string | number;
  /** 所属任务主键 */
  taskId: string | number;
  /** 任务名称快照 */
  taskName?: string;
  /** 智能体主键快照 */
  agentId?: string | number;
  /** 本次执行产生的会话（ai_session.id） */
  sessionId?: string | number;
  /** 本次执行的助手消息（ai_message.id） */
  messageId?: string | number;
  /** 触发方式（0定时 1手动） */
  triggerType?: string;
  /** 状态（0成功 1失败 2执行中） */
  status?: string;
  /** 开始时间 */
  startTime?: string;
  /** 结束时间 */
  endTime?: string;
  /** 执行耗时（毫秒） */
  durationMs?: number;
  /** 输入 Token 数 */
  promptTokens?: number;
  /** 输出 Token 数 */
  completionTokens?: number;
  /** 总 Token 数 */
  totalTokens?: number;
  /** 工具调用次数 */
  toolCallCount?: number;
  /** 失败原因 */
  errorMsg?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
}
