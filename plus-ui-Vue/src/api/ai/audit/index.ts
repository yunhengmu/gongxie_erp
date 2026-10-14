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

/**
 * 审计保留策略视图：保留天数 + 各表超期行数（清理后另带逐表删除行数）
 */
export interface AuditRetentionVO {
  /** 保留天数（0＝不限，不清理） */
  retentionDays?: number;
  /** 保留截止时间（早于该时间的行会被清理；不限时为空） */
  cutoff?: string | null;
  /** 各表当前超期行数：键＝表名 */
  pending?: Record<string, number>;
  /** 本次清理各表实际删除行数（未执行清理时为空） */
  purged?: Record<string, number> | null;
}

/**
 * 查询当前审计保留策略与各表超期行数
 */
export const getAuditRetention = (): AxiosPromise<AuditRetentionVO> => {
  return request({
    url: '/ai/audit/retention',
    method: 'get'
  });
};

/**
 * 保存审计保留策略（运行期生效，下一次清理即按新天数执行）
 * @param retentionDays 保留天数（0＝不限，不清理）
 */
export const saveAuditRetention = (retentionDays: number): AxiosPromise<void> => {
  return request({
    url: '/ai/audit/retention',
    method: 'put',
    data: { retentionDays }
  });
};

/**
 * 按当前保留策略清理超期数据（物理删除，返回逐表删除行数）
 */
export const purgeAuditRetention = (): AxiosPromise<AuditRetentionVO> => {
  return request({
    url: '/ai/audit/retention/purge',
    method: 'post'
  });
};
