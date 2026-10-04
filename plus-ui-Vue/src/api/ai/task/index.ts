import type { TaskForm, TaskQuery, TaskRunVO, TaskVO } from '@/api/ai/task/types';
import type { AgentVO } from '@/api/ai/agent/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询定时任务列表
 * @param query 查询条件
 */
export const listTask = (query?: TaskQuery): AxiosPromise<PageResult<TaskVO>> => {
  return request({
    url: '/ai/task/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询启用中的智能体选项（供任务选择运行的智能体）
 */
export const optionTaskAgent = (): AxiosPromise<AgentVO[]> => {
  return request({
    url: '/ai/task/agent/options',
    method: 'get'
  });
};

/**
 * 查询定时任务详情
 * @param id 主键
 */
export const getTask = (id: string | number): AxiosPromise<TaskVO> => {
  return request({
    url: '/ai/task/' + id,
    method: 'get'
  });
};

/**
 * 查询任务执行记录（分页，倒序）
 * @param taskId 任务主键
 * @param query 分页参数
 */
export const listTaskRun = (taskId: string | number, query?: PageQuery): AxiosPromise<PageResult<TaskRunVO>> => {
  return request({
    url: `/ai/task/${taskId}/runs`,
    method: 'get',
    params: query
  });
};

/**
 * 新增定时任务
 * @param data 表单数据
 */
export const addTask = (data: TaskForm) => {
  return request({
    url: '/ai/task',
    method: 'post',
    data: data
  });
};

/**
 * 修改定时任务
 * @param data 表单数据
 */
export const updateTask = (data: TaskForm) => {
  return request({
    url: '/ai/task',
    method: 'put',
    data: data
  });
};

/**
 * 删除定时任务（保留历史执行记录）
 * @param id 主键或主键数组
 */
export const delTask = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/task/' + id,
    method: 'delete'
  });
};

/**
 * 立即执行一次任务（不影响 Cron 排期）
 * @param taskId 任务主键
 */
export const runTaskNow = (taskId: string | number) => {
  return request({
    url: `/ai/task/${taskId}/run`,
    method: 'post'
  });
};
