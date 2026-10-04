import type { AgentVO } from '@/api/ai/agent/types';
import type { SessionMessageVO, SessionQuery, SessionVO, ToolCallLogVO } from '@/api/ai/session/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询会话列表（全部用户，按筛选条件）
 * @param query 查询条件
 */
export const listChatSessions = (query?: SessionQuery): AxiosPromise<PageResult<SessionVO>> => {
  return request({
    url: '/ai/session/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询出现过会话的智能体选项（含已停用，供按智能体筛选）
 */
export const optionSessionAgent = (): AxiosPromise<AgentVO[]> => {
  return request({
    url: '/ai/session/agent/options',
    method: 'get'
  });
};

/**
 * 查询会话消息记录
 * @param sessionId 会话主键
 */
export const listSessionMessage = (sessionId: string | number): AxiosPromise<SessionMessageVO[]> => {
  return request({
    url: `/ai/session/${sessionId}/messages`,
    method: 'get'
  });
};

/**
 * 查询会话工具调用明细
 * @param sessionId 会话主键
 */
export const listSessionToolCall = (sessionId: string | number): AxiosPromise<ToolCallLogVO[]> => {
  return request({
    url: `/ai/session/${sessionId}/toolCalls`,
    method: 'get'
  });
};

/**
 * 终止会话
 * @param sessionId 会话主键
 */
export const closeChatSessionById = (sessionId: string | number) => {
  return request({
    url: `/ai/session/${sessionId}/close`,
    method: 'put'
  });
};

/**
 * 删除会话（逻辑删除会话与消息）
 * @param id 主键或主键数组
 */
export const delChatSession = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/session/' + id,
    method: 'delete'
  });
};

/**
 * 导出会话留档地址（按当前筛选条件）
 */
export const exportSessionUrl = '/ai/session/export';
