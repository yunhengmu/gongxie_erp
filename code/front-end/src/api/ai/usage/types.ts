/**
 * AI 用量与配额类型定义
 */

export interface UsageStatVO {
  /** 主键 */
  id: string | number;
  /** 统计日期 */
  statDate: string;
  /** 智能体主键 */
  agentId?: string | number;
  /** 智能体名称 */
  agentName?: string;
  /** 模型主键 */
  modelId?: string | number;
  /** 模型名称 */
  modelName?: string;
  /** 用户主键 */
  userId?: string | number;
  /** 用户昵称 */
  userName?: string;
  /** 输入 Token 数 */
  promptTokens?: number;
  /** 输出 Token 数 */
  completionTokens?: number;
  /** 总 Token 数 */
  totalTokens?: number;
  /** 请求（对话轮次）数 */
  requestCount?: number;
  /** 工具调用次数 */
  toolCallCount?: number;
  /** 费用估算（元） */
  cost?: number;
  /** 模型是否已配置单价 */
  priceConfigured?: boolean;
  /** 更新时间 */
  updateTime?: string;
}

export interface UsageSummaryVO {
  /** 分组键 */
  dimension: string;
  /** 分组显示名 */
  label: string;
  /** 输入 Token 数 */
  promptTokens?: number;
  /** 输出 Token 数 */
  completionTokens?: number;
  /** 总 Token 数 */
  totalTokens?: number;
  /** 请求数 */
  requestCount?: number;
  /** 工具调用次数 */
  toolCallCount?: number;
  /** 费用估算（元） */
  cost?: number;
}

export interface UsageOverviewVO {
  /** 合计 */
  total?: UsageSummaryVO;
  /** 按日期分组 */
  byDate: UsageSummaryVO[];
  /** 按智能体分组 */
  byAgent: UsageSummaryVO[];
  /** 按模型分组 */
  byModel: UsageSummaryVO[];
}

export interface UsageStatQuery extends PageQuery {
  /** 统计日期起（含） */
  beginDate?: string;
  /** 统计日期止（含） */
  endDate?: string;
  /** 智能体主键 */
  agentId?: string | number;
  /** 模型主键 */
  modelId?: string | number;
  /** 用户主键 */
  userId?: string | number;
}