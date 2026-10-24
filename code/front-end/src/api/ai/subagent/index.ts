import type { SubagentForm, SubagentOptionVO, SubagentQuery, SubagentVO } from '@/api/ai/subagent/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询子智能体列表
 * @param query 查询条件
 */
export const listSubagent = (query?: SubagentQuery): AxiosPromise<PageResult<SubagentVO>> => {
  return request({
    url: '/ai/subagent/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询启用中的子智能体选项（供智能体绑定子智能体）
 */
export const subagentOptions = (): AxiosPromise<SubagentOptionVO[]> => {
  return request({
    url: '/ai/subagent/options',
    method: 'get'
  });
};

/**
 * 查询子智能体详情
 * @param id 主键
 */
export const getSubagent = (id: string | number): AxiosPromise<SubagentVO> => {
  return request({
    url: '/ai/subagent/' + id,
    method: 'get'
  });
};

/**
 * 新增子智能体
 * @param data 表单数据
 */
export const addSubagent = (data: SubagentForm) => {
  return request({
    url: '/ai/subagent',
    method: 'post',
    data: data
  });
};

/**
 * 修改子智能体（子智能体标识不可修改）
 * @param data 表单数据
 */
export const updateSubagent = (data: SubagentForm) => {
  return request({
    url: '/ai/subagent',
    method: 'put',
    data: data
  });
};

/**
 * 删除子智能体（物理删除，被智能体绑定时后端拒绝）
 * @param id 主键或主键数组
 */
export const delSubagent = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/subagent/' + id,
    method: 'delete'
  });
};