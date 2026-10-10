import type {
  AiRouteRunDetailVO,
  ChatAttachmentBo,
  ChatAttachmentVO,
  ChatConfirmBody,
  ChatMessageVO,
  ChatPendingVO,
  ChatResourceItem,
  ChatResourceVO,
  ChatSessionQuery,
  ChatSessionVO
} from '@/api/ai/chat/types';
import type { ToolCallLogVO } from '@/api/ai/session/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import { getToken } from '@/utils/auth';
import request from '@/utils/request';

/**
 * 创建会话（未指定标题时后端自动生成）
 * @param agentId 智能体主键
 * @param scenarioPackId 所选场景包主键（可选：为空表示不选包，该会话不接入任何 MCP 工具）
 * @param title 会话标题，可空
 * @param pinnedExpertId 指定专家主键（可选；REQ-AI-31：非空表示本会话强制路由到该专家）
 */
export const createChatSession = (
  agentId: string | number,
  scenarioPackId?: string | number,
  title?: string,
  pinnedExpertId?: string | number
): AxiosPromise<ChatSessionVO> => {
  return request({
    url: '/ai/chat/session',
    method: 'post',
    params: { agentId, scenarioPackId, title, pinnedExpertId }
  });
};

/**
 * 查询会话级资源集（REQ-AI-32，含展示名，供标签 chip 渲染）
 * @param sessionId 会话主键
 */
export const listChatResources = (sessionId: string | number): AxiosPromise<ChatResourceVO[]> => {
  return request({
    url: `/ai/chat/session/${sessionId}/resources`,
    method: 'get'
  });
};

/**
 * 批量增会话级资源（REQ-AI-32；重复项幂等）
 * @param sessionId 会话主键
 * @param items 资源项集合
 */
export const addChatResources = (
  sessionId: string | number,
  items: ChatResourceItem[]
): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/resources`,
    method: 'post',
    data: { items }
  });
};

/**
 * 批量删会话级资源（REQ-AI-32；逻辑删除即移除）
 * @param sessionId 会话主键
 * @param items 资源项集合
 */
export const removeChatResources = (
  sessionId: string | number,
  items: ChatResourceItem[]
): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/resources`,
    method: 'delete',
    data: { items }
  });
};

/**
 * 查询会话详情（会话头展示「已指定：X」，REQ-AI-31）
 * @param sessionId 会话主键
 */
export const getChatSession = (sessionId: string | number): AxiosPromise<ChatSessionVO> => {
  return request({
    url: `/ai/chat/session/${sessionId}`,
    method: 'get'
  });
};

/**
 * 指定 / 取消指定专家（REQ-AI-31：仅本人会话，会话内可变更）
 * @param sessionId 会话主键
 * @param expertAgentId 专家主键；传 null / undefined 表示取消指定
 */
export const pinChatSessionExpert = (
  sessionId: string | number,
  expertAgentId?: string | number | null
): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/expert`,
    method: 'put',
    data: { expertAgentId: expertAgentId ?? null }
  });
};

/**
 * 查询我的会话列表
 * @param query 查询条件
 */
export const listChatSession = (query?: ChatSessionQuery): AxiosPromise<PageResult<ChatSessionVO>> => {
  return request({
    url: '/ai/chat/session/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询会话消息列表
 * @param sessionId 会话主键
 */
export const listChatMessage = (sessionId: string | number): AxiosPromise<ChatMessageVO[]> => {
  return request({
    url: `/ai/chat/session/${sessionId}/messages`,
    method: 'get'
  });
};

/**
 * 查询会话历史工具调用明细（用于历史会话还原工具卡片）
 * @param sessionId 会话主键
 */
export const listChatToolCall = (sessionId: string | number): AxiosPromise<ToolCallLogVO[]> => {
  return request({
    url: `/ai/chat/session/${sessionId}/toolCalls`,
    method: 'get'
  });
};

/**
 * 查询会话的编排轨迹与专家调用明细（REQ-AI-30）
 * @param sessionId 会话主键
 */
export const listChatRouteRuns = (sessionId: string | number): AxiosPromise<AiRouteRunDetailVO[]> => {
  return request({
    url: `/ai/chat/session/${sessionId}/routeRuns`,
    method: 'get'
  });
};

/**
 * 发送用户消息（先落库，返回带 messageId 的消息）
 * @param sessionId 会话主键
 * @param content 消息正文（可为空，此时须带附件）
 * @param attachmentIds 随消息发送的附件主键集合（可选）
 */
export const sendChatMessage = (
  sessionId: string | number,
  content: string,
  attachmentIds?: Array<string | number>
): AxiosPromise<ChatMessageVO> => {
  return request({
    url: `/ai/chat/session/${sessionId}/message`,
    method: 'post',
    data: { content, attachmentIds }
  });
};

/**
 * 登记聊天附件（文件本体先经框架 OSS 上传，这里按 ossId 校验类型/大小并（文档）解析）
 * @param sessionId 会话主键
 * @param data 附件登记参数（ossId + 原始文件名）
 */
export const registerChatAttachment = (
  sessionId: string | number,
  data: ChatAttachmentBo
): AxiosPromise<ChatAttachmentVO> => {
  return request({
    url: `/ai/chat/session/${sessionId}/attachment`,
    method: 'post',
    data
  });
};

/**
 * 删除尚未发送的暂存附件
 * @param sessionId 会话主键
 * @param attachmentId 附件主键
 */
export const deleteChatAttachment = (sessionId: string | number, attachmentId: string | number): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/attachment/${attachmentId}`,
    method: 'delete'
  });
};

/**
 * 删除一问一答（整轮）：该用户消息及其助手回复
 * @param sessionId 会话主键
 * @param messageId 用户消息主键
 */
export const deleteChatMessage = (sessionId: string | number, messageId: string | number): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/message/${messageId}`,
    method: 'delete'
  });
};

/**
 * 终止会话
 * @param sessionId 会话主键
 */
export const closeChatSession = (sessionId: string | number): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}`,
    method: 'delete'
  });
};

/**
 * 重命名会话
 * @param sessionId 会话主键
 * @param title 新标题
 */
export const renameChatSession = (sessionId: string | number, title: string): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/title`,
    method: 'put',
    params: { title }
  });
};

/**
 * 置顶 / 取消置顶会话
 * @param sessionId 会话主键
 * @param top 是否置顶
 */
export const topChatSession = (sessionId: string | number, top: boolean): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/top`,
    method: 'put',
    params: { top }
  });
};

/**
 * 记录用户对「需人工确认」工具调用的处置结果
 * @param sessionId 会话主键
 * @param data 确认参数
 */
export const confirmChatTool = (sessionId: string | number, data: ChatConfirmBody): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/confirm`,
    method: 'post',
    data
  });
};

/**
 * 停止该会话当前正在进行的流式执行（对话页「停止」按钮）
 * @param sessionId 会话主键
 */
export const stopChat = (sessionId: string | number): AxiosPromise<void> => {
  return request({
    url: `/ai/chat/session/${sessionId}/stop`,
    method: 'post'
  });
};

/**
 * 查询会话内未决的工具确认（刷新页面后恢复确认卡片）
 * @param sessionId 会话主键
 */
export const listChatPending = (sessionId: string | number): AxiosPromise<ChatPendingVO[]> => {
  return request({
    url: `/ai/chat/session/${sessionId}/pending`,
    method: 'get'
  });
};

/**
 * 拼接 SSE 流地址（鉴权参数走 query，与 push.ts 同款拼法）
 * @param sessionId 会话主键
 * @param messageId 已落库的用户消息主键
 */
export const buildChatStreamUrl = (sessionId: string | number, messageId: string | number) => {
  return `${import.meta.env.VITE_APP_BASE_API}/ai/chat/stream?sessionId=${sessionId}&messageId=${messageId}&Authorization=Bearer ${getToken()}&clientid=${import.meta.env.VITE_APP_CLIENT_ID}`;
};

/**
 * 拼接 HITL 恢复流地址
 * @param sessionId 会话主键
 * @param messageId 暂停时落库的助手消息主键
 */
export const buildChatResumeUrl = (sessionId: string | number, messageId: string | number) => {
  return `${import.meta.env.VITE_APP_BASE_API}/ai/chat/resume?sessionId=${sessionId}&messageId=${messageId}&Authorization=Bearer ${getToken()}&clientid=${import.meta.env.VITE_APP_CLIENT_ID}`;
};
