import type { McpClientForm, McpClientQuery, McpClientTestResult, McpClientVO } from '@/api/ai/mcp/client/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询外部 MCP 连接列表
 * @param query 查询条件
 */
export const listMcpClient = (query?: McpClientQuery): AxiosPromise<PageResult<McpClientVO>> => {
  return request({
    url: '/ai/mcp/client/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询外部 MCP 连接详情
 * @param id 主键
 */
export const getMcpClient = (id: string | number): AxiosPromise<McpClientVO> => {
  return request({
    url: '/ai/mcp/client/' + id,
    method: 'get'
  });
};

/**
 * 新增外部 MCP 连接
 * @param data 表单数据
 */
export const addMcpClient = (data: McpClientForm) => {
  return request({
    url: '/ai/mcp/client',
    method: 'post',
    data
  });
};

/**
 * 修改外部 MCP 连接
 * @param data 表单数据
 */
export const updateMcpClient = (data: McpClientForm) => {
  return request({
    url: '/ai/mcp/client',
    method: 'put',
    data
  });
};

/**
 * 删除外部 MCP 连接
 * @param id 主键或主键数组
 */
export const delMcpClient = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/mcp/client/' + id,
    method: 'delete'
  });
};

/**
 * 测试连接并列出该 Server 声明的工具
 * @param data 连接配置（编辑时可带主键以复用已配置请求头）
 */
export const testMcpClient = (data: McpClientForm): AxiosPromise<McpClientTestResult> => {
  return request({
    url: '/ai/mcp/client/test',
    method: 'post',
    data
  });
};