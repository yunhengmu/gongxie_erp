import type { PermissionDecisionQuery, PermissionDecisionVO } from '@/api/ai/permission/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询工具权限决策日志
 * @param query 查询条件
 */
export const listPermissionDecision = (
  query?: PermissionDecisionQuery
): AxiosPromise<PageResult<PermissionDecisionVO>> => {
  return request({
    url: '/ai/permission/decision/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询当前全局权限模式
 */
export const getPermissionMode = (): AxiosPromise<string> => {
  return request({
    url: '/ai/permission/mode',
    method: 'get'
  });
};

/**
 * 切换全局权限模式
 * @param mode AgentScope PermissionMode 名称
 */
export const updatePermissionMode = (mode: string): AxiosPromise<void> => {
  return request({
    url: '/ai/permission/mode',
    method: 'put',
    params: { mode }
  });
};