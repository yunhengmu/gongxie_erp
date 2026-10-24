import type { WorkspaceConfig, WorkspaceContent, WorkspaceContentForm, WorkspaceFile } from '@/api/ai/workspace/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询当前生效的工作区上下文配置
 */
export const getWorkspaceConfig = (): AxiosPromise<WorkspaceConfig> => {
  return request({
    url: '/ai/workspace/config',
    method: 'get'
  });
};

/**
 * 保存工作区上下文配置（运行期生效）
 * @param data 工作区上下文配置
 */
export const updateWorkspaceConfig = (data: WorkspaceConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/workspace/config',
    method: 'put',
    data
  });
};

/**
 * 查询指定智能体与用户下的工作区文件清单
 * @param agentId 智能体主键
 * @param userId 用户主键
 */
export const listWorkspaceFiles = (agentId: string | number, userId: string | number): AxiosPromise<WorkspaceFile[]> => {
  return request({
    url: '/ai/workspace/files',
    method: 'get',
    params: { agentId, userId }
  });
};

/**
 * 读取指定层的工作区文件内容
 * @param agentId 智能体主键
 * @param userId 用户主键
 * @param path 工作区相对路径
 * @param scope 层来源（SHARED / USER）
 */
export const getWorkspaceContent = (
  agentId: string | number,
  userId: string | number,
  path: string,
  scope: string
): AxiosPromise<WorkspaceContent> => {
  return request({
    url: '/ai/workspace/content',
    method: 'get',
    params: { agentId, userId, path, scope }
  });
};

/**
 * 保存指定层的工作区文件内容
 * @param data 写入参数
 */
export const updateWorkspaceContent = (data: WorkspaceContentForm): AxiosPromise<void> => {
  return request({
    url: '/ai/workspace/content',
    method: 'put',
    data
  });
};

/**
 * 清除用户覆盖，使该文件回落共享默认
 * @param agentId 智能体主键
 * @param userId 用户主键
 * @param path 工作区相对路径
 */
export const resetWorkspaceOverride = (
  agentId: string | number,
  userId: string | number,
  path: string
): AxiosPromise<void> => {
  return request({
    url: '/ai/workspace/content',
    method: 'delete',
    params: { agentId, userId, path }
  });
};