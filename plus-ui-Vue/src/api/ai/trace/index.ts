import type { TraceQuery, TraceVO } from '@/api/ai/trace/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询链路追踪列表
 * @param query 查询条件
 */
export const listTrace = (query?: TraceQuery): AxiosPromise<PageResult<TraceVO>> => {
  return request({
    url: '/ai/trace/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询链路追踪详情（含上下文清单与状态迁移时间线）
 * @param id 链路主键
 */
export const getTrace = (id: string | number): AxiosPromise<TraceVO> => {
  return request({
    url: `/ai/trace/${id}`,
    method: 'get'
  });
};