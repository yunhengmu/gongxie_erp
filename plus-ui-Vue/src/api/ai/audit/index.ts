import type { AgentVO } from '@/api/ai/agent/types';
import type {
  MessageAuditQuery,
  MessageAuditVO,
  ToolCallAuditQuery,
  ToolCallAuditVO,
  ToolCallStatVO
} from '@/api/ai/audit/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询会话与消息审计列表
 * @param query 查询条件
 */
export const listMessageAudit = (query?: MessageAuditQuery): AxiosPromise<PageResult<MessageAuditVO>> => {
  return request({
    url: '/ai/audit/message/list',
    method: 'get',
    params: query
  });
};

/**
 * 导出消息审计留档地址（按当前筛选条件）
 */
export const exportMessageAuditUrl = '/ai/audit/message/export';

/**
 * 查询工具调用明细列表
 * @param query 查询条件
 */
export const listToolCallAudit = (query?: ToolCallAuditQuery): AxiosPromise<PageResult<ToolCallAuditVO>> => {
  return request({
    url: '/ai/audit/toolCall/list',
    method: 'get',
    params: query
  });
};

/**
 * 按工具维度统计调用次数与失败率（与明细同一份筛选条件，不分页）
 * @param query 查询条件
 */
export const toolCallStats = (query?: ToolCallAuditQuery): AxiosPromise<ToolCallStatVO[]> => {
  return request({
    url: '/ai/audit/toolCall/stats',
    method: 'get',
    params: query
  });
};

/**
 * 查询「运行数据中出现过的智能体」选项（含已停用，供工具调用明细 / 编排运行两页的智能体筛选）
 */
export const optionAuditAgents = (): AxiosPromise<AgentVO[]> => {
  return request({
    url: '/ai/audit/agent/options',
    method: 'get'
  });
};
