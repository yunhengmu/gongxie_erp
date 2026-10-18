/**
 * AI 平台运行日报类型定义
 */

export interface AiDailyDigestVO {
  /** 主键 */
  id: string | number;
  /** 日报日期（yyyy-MM-dd） */
  digestDate?: string;
  /** 结论级别（0 平稳 / 1 需关注 / 2 需处理 / null 未定级） */
  level?: string | null;
  /** 日报正文（人读摘要，可能含换行） */
  digestText?: string;
  /** 总 Token 数 */
  usageTotalTokens?: number;
  /** 请求数 */
  usageRequestCount?: number;
  /** 工具调用数 */
  usageToolCallCount?: number;
  /** 用量环比（%） */
  usageComparePct?: number | null;
  /** 配额使用率（%） */
  quotaUsagePct?: number | null;
  /** 任务执行次数 */
  taskRunCount?: number;
  /** 任务失败次数 */
  taskFailCount?: number;
  /** 待办积压条数 */
  backlogCount?: number;
  /** 积压最多节点 */
  backlogTopNode?: string | null;
  /** 最久滞留时长（分钟） */
  backlogMaxWaitMinutes?: number | null;
  /** 权限允许次数 */
  permAllowCount?: number;
  /** 权限需确认次数 */
  permAskCount?: number;
  /** 权限拒绝次数 */
  permDenyCount?: number;
  /** 登录成功次数 */
  loginSuccessCount?: number;
  /** 登录失败次数 */
  loginFailCount?: number;
  /** 新 IP 登录次数 */
  loginAnomalyCount?: number;
  /** 来源任务执行主键 */
  taskRunId?: string | number | null;
  /** 来源会话主键 */
  sessionId?: string | number | null;
  /** 备注 */
  remark?: string | null;
  /** 落库时间 */
  createTime?: string;
}

export interface AiDailyDigestQuery extends PageQuery {
  /** 日报日期起（yyyy-MM-dd） */
  beginDate?: string;
  /** 日报日期止（yyyy-MM-dd） */
  endDate?: string;
  /** 结论级别（0 平稳 / 1 需关注 / 2 需处理） */
  level?: string;
}
