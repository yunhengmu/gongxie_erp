import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 对话偏好（当前用户级的时区 / 语言，覆盖平台默认；REQ-AI-31）
 */
export interface ChatPref {
  /** 平台默认时区（只读） */
  platformTimezone?: string;
  /** 平台默认语言（只读） */
  platformLocale?: string;
  /** 我已设置时区（未设置为空） */
  timezone?: string;
  /** 我已设置语言（未设置为空） */
  locale?: string;
  /** 实际生效时区（我设置优先，否则平台默认） */
  effectiveTimezone?: string;
  /** 实际生效语言（我设置优先，否则平台默认） */
  effectiveLocale?: string;
}

/**
 * 查询当前用户的对话偏好（回带平台默认 / 已设置 / 实际生效三段值）
 */
export const getChatPref = (): AxiosPromise<ChatPref> => {
  return request({
    url: '/ai/chat/pref',
    method: 'get'
  });
};

/**
 * 保存当前用户的对话偏好（时区 / 语言，留空表示该项不设置）
 * @param data 对话偏好
 */
export const saveChatPref = (data: ChatPref): AxiosPromise<void> => {
  return request({
    url: '/ai/chat/pref',
    method: 'put',
    data
  });
};

/**
 * 清空当前用户的人级设置，使两项均回落平台默认
 */
export const clearChatPref = (): AxiosPromise<void> => {
  return request({
    url: '/ai/chat/pref',
    method: 'delete'
  });
};
