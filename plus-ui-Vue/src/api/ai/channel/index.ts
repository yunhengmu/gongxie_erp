import type {
  ChannelForm,
  ChannelIdentityForm,
  ChannelIdentityQuery,
  ChannelIdentityVO,
  ChannelQuery,
  ChannelThreadVO,
  ChannelUserLookupVO,
  ChannelVO
} from '@/api/ai/channel/types';
import type { AgentVO } from '@/api/ai/agent/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询渠道列表
 * @param query 查询条件
 */
export const listChannel = (query?: ChannelQuery): AxiosPromise<PageResult<ChannelVO>> => {
  return request({
    url: '/ai/channel/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询启用中的智能体选项（供渠道选择默认智能体）
 */
export const optionChannelAgent = (): AxiosPromise<AgentVO[]> => {
  return request({
    url: '/ai/channel/agent/options',
    method: 'get'
  });
};

/**
 * 查询渠道详情
 * @param id 主键
 */
export const getChannel = (id: string | number): AxiosPromise<ChannelVO> => {
  return request({
    url: '/ai/channel/' + id,
    method: 'get'
  });
};

/**
 * 新增渠道
 * @param data 表单数据
 */
export const addChannel = (data: ChannelForm) => {
  return request({
    url: '/ai/channel',
    method: 'post',
    data: data
  });
};

/**
 * 修改渠道（凭据留空表示保持原值）
 * @param data 表单数据
 */
export const updateChannel = (data: ChannelForm) => {
  return request({
    url: '/ai/channel',
    method: 'put',
    data: data
  });
};

/**
 * 删除渠道
 * @param id 主键或主键数组
 */
export const delChannel = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/channel/' + id,
    method: 'delete'
  });
};

/**
 * 按手机号查询外部用户标识（仅钉钉 / 飞书渠道支持）
 * @param id 渠道主键
 * @param mobile 11 位手机号
 */
export const findExternalUserId = (id: string | number, mobile: string): AxiosPromise<ChannelUserLookupVO> => {
  return request({
    url: '/ai/channel/' + id + '/external-user-id',
    method: 'get',
    params: { mobile }
  });
};

/**
 * 查询外部身份映射列表
 * @param query 查询条件
 */
export const listIdentity = (query?: ChannelIdentityQuery): AxiosPromise<PageResult<ChannelIdentityVO>> => {
  return request({
    url: '/ai/channel/identity/list',
    method: 'get',
    params: query
  });
};

/**
 * 新增外部身份映射
 * @param data 表单数据
 */
export const addIdentity = (data: ChannelIdentityForm) => {
  return request({
    url: '/ai/channel/identity',
    method: 'post',
    data: data
  });
};

/**
 * 修改外部身份映射
 * @param data 表单数据
 */
export const updateIdentity = (data: ChannelIdentityForm) => {
  return request({
    url: '/ai/channel/identity',
    method: 'put',
    data: data
  });
};

/**
 * 删除外部身份映射
 * @param id 主键或主键数组
 */
export const delIdentity = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/channel/identity/' + id,
    method: 'delete'
  });
};

/**
 * 查询消息线程与会话映射列表
 * @param query 查询条件
 */
export const listThread = (query?: PageQuery & { channelId?: string | number }): AxiosPromise<PageResult<ChannelThreadVO>> => {
  return request({
    url: '/ai/channel/thread/list',
    method: 'get',
    params: query
  });
};

/**
 * 删除消息线程与会话映射
 * @param id 主键或主键数组
 */
export const delThread = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/channel/thread/' + id,
    method: 'delete'
  });
};