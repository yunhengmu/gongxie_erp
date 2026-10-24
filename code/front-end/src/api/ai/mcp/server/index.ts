import type { McpServerConfigForm, McpServerStatus, McpServerTool } from '@/api/ai/mcp/server/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询 MCP 出口状态（端点、口令校验开关与口令、当前实际挂载的工具名）
 */
export const getMcpServer = (): AxiosPromise<McpServerStatus> => {
  return request({
    url: '/ai/mcp/server',
    method: 'get'
  });
};

/**
 * 保存 MCP 出口访问配置（运行期生效）
 * @param data 访问配置
 */
export const updateMcpServer = (data: McpServerConfigForm): AxiosPromise<void> => {
  return request({
    url: '/ai/mcp/server',
    method: 'put',
    data
  });
};

/**
 * 查询对外暴露候选工具清单（启用中的只读工具）与暴露状态
 */
export const listMcpServerTools = (): AxiosPromise<McpServerTool[]> => {
  return request({
    url: '/ai/mcp/server/tools',
    method: 'get'
  });
};

/**
 * 保存对外暴露清单（热生效）
 * @param toolCodes 需要暴露的工具编码集合
 */
export const updateMcpServerTools = (toolCodes: Array<string | number>): AxiosPromise<void> => {
  return request({
    url: '/ai/mcp/server/tools',
    method: 'put',
    data: { toolCodes }
  });
};