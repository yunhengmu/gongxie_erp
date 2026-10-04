/**
 * AI 编排运行类型定义（REQ-AI-30 Phase 2 第三项：入口智能体每轮编排一行）
 */

export interface RouteRunVO {
  /** 编排主键 */
  id?: string | number;
  /** 会话主键 */
  sessionId?: string | number;
  /** 本轮助手消息主键（待确认时也落） */
  messageId?: string | number | null;
  /** 入口智能体主键 */
  entryAgentId?: string | number;
  /** 入口智能体名称 */
  entryAgentName?: string;
  /** 入口智能体编码 */
  entryAgentCode?: string;
  /** 提问用户主键 */
  userId?: string | number | null;
  /** 提问用户昵称 */
  userName?: string;
  /** 会话标题 */
  sessionTitle?: string;
  /** 路由动作（self 自答 / route 路由） */
  action?: string;
  /** 本轮参与的专家名称（顿号连接） */
  expertAgentNames?: string;
  /** 专家调用次数（含重试与换专家） */
  expertCallCount?: number | null;
  /** 编排状态（running / pending / success / degraded / aborted） */
  status?: string;
  /** 降级或中止原因 */
  degradeReason?: string;
  /** 编排告警汇总（只告警不阻断：L3 脱敏命中 + 事实篡改抽查；仅详情接口返回） */
  warningText?: string;
  /** 编排总耗时（毫秒） */
  durationMs?: number | null;
  /** 入口轮 Token（含整合轮） */
  entryTokens?: number | null;
  /** 专家轮 Token */
  expertTokens?: number | null;
  /** 合计 Token */
  totalTokens?: number | null;
  /** 创建时间 */
  createTime?: string;
  /** 路由决策原文（仅详情接口返回） */
  decisionJson?: string;
  /** 下发给专家的任务片段（仅详情接口返回） */
  taskContext?: string;
  /** 仅详情接口返回：专家调用明细 */
  expertCalls?: RouteExpertCallVO[];
  /** 仅详情接口返回：入口轮输入 Token */
  entryPromptTokens?: number | null;
  /** 仅详情接口返回：入口轮输出 Token */
  entryCompletionTokens?: number | null;
}

export interface RouteExpertCallVO {
  /** 主键 */
  id?: string | number;
  /** 专家智能体主键 */
  expertAgentId?: string | number;
  /** 专家名称 */
  expertAgentName?: string;
  /** 专家编码 */
  expertAgentKey?: string;
  /** 专家独立会话标识 */
  expertSessionId?: string;
  /** 第几次尝试 */
  attemptNo?: number | null;
  /** 调用类型（first / resume；历史数据可能含 retry / switch） */
  callKind?: string;
  /** 本次下发的输出契约（**已废弃**：专家直答修订后停写，仅存量数据有值） */
  contractJson?: string;
  /** 专家输出正文（原文留证） */
  rawOutput?: string;
  /** 通过契约校验后的结构化结果（**已废弃**：专家直答修订后停写） */
  parsedOutput?: string;
  /** 失败原因（专家直答修订后为「专家不可用原因」；历史数据为结构校验失败原因） */
  validationError?: string;
  /** 调用状态（success 已回答 / failed 调用失败 / timeout 超时 / pending_confirm 待确认；历史数据可能含 invalid / partial） */
  status?: string;
  /** 输入 Token */
  promptTokens?: number | null;
  /** 输出 Token */
  completionTokens?: number | null;
  /** 耗时（毫秒） */
  durationMs?: number | null;
  /** 创建时间 */
  createTime?: string;
}

export interface RouteRunStatsVO {
  /** 编排总数 */
  totalCount?: number | null;
  /** 自答数 */
  selfCount?: number | null;
  /** 路由数（route + pinned 合计，同属「路由类」） */
  routeCount?: number | null;
  /** 其中用户指定（pinned，REQ-AI-31） */
  pinnedCount?: number | null;
  /** 执行中 */
  runningCount?: number | null;
  /** 成功 */
  successCount?: number | null;
  /** 降级 */
  degradedCount?: number | null;
  /** 中止 */
  abortedCount?: number | null;
  /** 待人工确认 */
  pendingCount?: number | null;
  /** 路由命中率（%） */
  routeHitRate?: number | null;
  /** 降级率（%） */
  degradeRate?: number | null;
  /** 专家调用总次数 */
  expertCallTotal?: number | null;
  /** 编排总耗时（毫秒） */
  totalDurationMs?: number | null;
  /** 平均耗时（毫秒） */
  avgDurationMs?: number | null;
  /** 专家轮输入 Token */
  expertPromptTokens?: number | null;
  /** 专家轮输出 Token */
  expertCompletionTokens?: number | null;
  /** 入口轮 Token */
  entryTokens?: number | null;
  /** 合计 Token */
  totalTokens?: number | null;
}

export interface RouteRunQuery extends PageQuery {
  /** 会话主键（供从任务工作台 / 链路追踪深链直达） */
  sessionId?: string | number;
  /** 消息主键 */
  messageId?: string | number;
  /** 入口智能体主键 */
  entryAgentId?: string | number;
  /** 提问用户主键 */
  userId?: string | number;
  /** 路由动作（self / route） */
  action?: string;
  /** 编排状态（running / pending / success / degraded / aborted） */
  status?: string;
  /** 参与的专家智能体主键（含已停用） */
  expertAgentId?: string | number;
  /** 时间起（YYYY-MM-DD HH:mm:ss） */
  beginTime?: string;
  /** 时间止（YYYY-MM-DD HH:mm:ss） */
  endTime?: string;
}