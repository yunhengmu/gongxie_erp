import type {
  MemoryConfig,
  MemoryContent,
  MemoryContentForm,
  MemoryDeclaredEntry,
  MemoryDeclaredForm,
  MemoryFile,
  MemoryRestoreForm,
  MemoryVersion
} from '@/api/ai/memory/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询当前生效的长期记忆配置
 */
export const getMemoryConfig = (): AxiosPromise<MemoryConfig> => {
  return request({
    url: '/ai/memory/config',
    method: 'get'
  });
};

/**
 * 保存长期记忆配置（运行期生效）
 * @param data 记忆配置
 */
export const updateMemoryConfig = (data: MemoryConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/memory/config',
    method: 'put',
    data
  });
};

/**
 * 查询指定智能体与用户下的记忆文件清单
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const listMemoryFiles = (agentId: string | number, userId: string | number): AxiosPromise<MemoryFile[]> => {
  return request({
    url: '/ai/memory/files',
    method: 'get',
    params: { agentId, userId }
  });
};

/**
 * 读取记忆文件内容
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 * @param path 工作区相对路径
 */
export const getMemoryContent = (
  agentId: string | number,
  userId: string | number,
  path: string
): AxiosPromise<MemoryContent> => {
  return request({
    url: '/ai/memory/content',
    method: 'get',
    params: { agentId, userId, path }
  });
};

/**
 * 保存记忆文件内容（人工编辑）
 * @param data 写入参数
 */
export const updateMemoryContent = (data: MemoryContentForm): AxiosPromise<void> => {
  return request({
    url: '/ai/memory/content',
    method: 'put',
    data
  });
};

/**
 * 手动触发记忆合并（把每日流水合并进 MEMORY.md）
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const consolidateMemory = (agentId: string | number, userId: string | number): AxiosPromise<void> => {
  return request({
    url: '/ai/memory/consolidate',
    method: 'post',
    params: { agentId, userId }
  });
};

/**
 * 归档超期记忆流水（移动到记忆目录下的 archive/ 子目录）
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const archiveMemory = (agentId: string | number, userId: string | number): AxiosPromise<number> => {
  return request({
    url: '/ai/memory/archive',
    method: 'post',
    params: { agentId, userId }
  });
};

/**
 * 清理超期记忆文件与会话日志
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const cleanupMemory = (agentId: string | number, userId: string | number): AxiosPromise<number> => {
  return request({
    url: '/ai/memory/cleanup',
    method: 'post',
    params: { agentId, userId }
  });
};

/**
 * 列出 MEMORY.md 历史版本
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const listMemoryVersions = (
  agentId: string | number,
  userId: string | number
): AxiosPromise<MemoryVersion[]> => {
  return request({
    url: '/ai/memory/versions',
    method: 'get',
    params: { agentId, userId }
  });
};

/**
 * 读取版本详情（含正文）
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 * @param versionId 版本 ID
 */
export const getMemoryVersion = (
  agentId: string | number,
  userId: string | number,
  versionId: string
): AxiosPromise<MemoryVersion> => {
  return request({
    url: `/ai/memory/versions/${versionId}`,
    method: 'get',
    params: { agentId, userId }
  });
};

/**
 * 恢复到指定版本
 * @param versionId 版本 ID
 * @param data 恢复参数
 */
export const restoreMemoryVersion = (
  versionId: string,
  data: MemoryRestoreForm
): AxiosPromise<MemoryVersion> => {
  return request({
    url: `/ai/memory/versions/${versionId}/restore`,
    method: 'post',
    data
  });
};

/**
 * 列出显式记忆（用户明确要求记住的条目，含已撤销）
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const listDeclaredMemories = (
  agentId: string | number,
  userId: string | number
): AxiosPromise<MemoryDeclaredEntry[]> => {
  return request({
    url: '/ai/memory/declared',
    method: 'get',
    params: { agentId, userId }
  });
};

/**
 * 新增一条显式记忆（人工补充，服务端仍会净化）
 * @param data 新增参数
 */
export const addDeclaredMemory = (data: MemoryDeclaredForm): AxiosPromise<MemoryDeclaredEntry> => {
  return request({
    url: '/ai/memory/declared',
    method: 'post',
    data
  });
};

/**
 * 撤销一条显式记忆
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 * @param entryId 条目主键
 */
export const revokeDeclaredMemory = (
  agentId: string | number,
  userId: string | number,
  entryId: string
): AxiosPromise<boolean> => {
  return request({
    url: `/ai/memory/declared/${entryId}`,
    method: 'delete',
    params: { agentId, userId }
  });
};

/**
 * 删除某用户的全部长期记忆（回滚 / 合规删除）
 * @param agentId 智能体主键
 * @param userId 记忆归属用户主键
 */
export const removeMemory = (agentId: string | number, userId: string | number): AxiosPromise<void> => {
  return request({
    url: `/ai/memory/${agentId}/${userId}`,
    method: 'delete'
  });
};