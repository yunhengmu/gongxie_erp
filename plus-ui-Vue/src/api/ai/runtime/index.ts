import type { CompactionConfig, PlanConfig } from '@/api/ai/runtime/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询当前生效的上下文压缩策略
 */
export const getCompactionConfig = (): AxiosPromise<CompactionConfig> => {
  return request({
    url: '/ai/runtime/compaction',
    method: 'get'
  });
};

/**
 * 保存上下文压缩策略（运行期生效）
 * @param data 压缩策略
 */
export const updateCompactionConfig = (data: CompactionConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/compaction',
    method: 'put',
    data
  });
};

/**
 * 查询当前生效的计划模式与任务清单配置
 */
export const getPlanConfig = (): AxiosPromise<PlanConfig> => {
  return request({
    url: '/ai/runtime/plan',
    method: 'get'
  });
};

/**
 * 保存计划模式与任务清单配置（运行期生效）
 * @param data 计划模式配置
 */
export const updatePlanConfig = (data: PlanConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/plan',
    method: 'put',
    data
  });
};