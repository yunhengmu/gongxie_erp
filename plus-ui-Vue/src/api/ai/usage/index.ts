import type { UsageOverviewVO, UsageStatQuery, UsageStatVO } from '@/api/ai/usage/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询用量明细
 * @param query 查询条件
 */
export const listUsageStat = (query?: UsageStatQuery): AxiosPromise<PageResult<UsageStatVO>> => {
  return request({
    url: '/ai/usage/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询用量总览（合计 + 按日期 / 智能体 / 模型分组）
 * @param query 查询条件
 */
export const usageOverview = (query?: UsageStatQuery): AxiosPromise<UsageOverviewVO> => {
  return request({
    url: '/ai/usage/overview',
    method: 'get',
    params: query
  });
};

/**
 * 导出用量明细地址（按当前筛选条件）
 */
export const exportUsageUrl = '/ai/usage/export';