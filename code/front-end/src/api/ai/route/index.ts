import type { RouteRunQuery, RouteRunStatsVO, RouteRunVO } from '@/api/ai/route/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询编排运行列表（REQ-AI-30 Phase 2 第三项）
 * @param query 查询条件
 */
export const listRouteRuns = (query?: RouteRunQuery): AxiosPromise<PageResult<RouteRunVO>> => {
  return request({
    url: '/ai/route/run/list',
    method: 'get',
    params: query
  });
};

/**
 * 编排运行统计（与列表同一筛选条件）
 * @param query 查询条件
 */
export const routeRunStats = (query?: RouteRunQuery): AxiosPromise<RouteRunStatsVO> => {
  return request({
    url: '/ai/route/run/stats',
    method: 'get',
    params: query
  });
};

/**
 * 查询编排运行详情（含全部专家调用明细与原始输出原文）
 * @param id 编排主键
 */
export const getRouteRun = (id: string | number): AxiosPromise<RouteRunVO> => {
  return request({
    url: `/ai/route/run/${id}`,
    method: 'get'
  });
};