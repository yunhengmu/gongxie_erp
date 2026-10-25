import type {
  SafetyEventQuery,
  SafetyEventVO,
  SafetyWordForm,
  SafetyWordQuery,
  SafetyWordVO
} from '@/api/ai/safety/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询敏感词库列表
 * @param query 查询条件
 */
export const listWord = (query?: SafetyWordQuery): AxiosPromise<PageResult<SafetyWordVO>> => {
  return request({
    url: '/ai/safety/word/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询敏感词详情
 * @param id 主键
 */
export const queryWord = (id: string | number): AxiosPromise<SafetyWordVO> => {
  return request({
    url: '/ai/safety/word/' + id,
    method: 'get'
  });
};

/**
 * 新增敏感词
 * @param data 表单数据
 */
export const addWord = (data: SafetyWordForm) => {
  return request({
    url: '/ai/safety/word',
    method: 'post',
    data: data
  });
};

/**
 * 修改敏感词
 * @param data 表单数据
 */
export const updateWord = (data: SafetyWordForm) => {
  return request({
    url: '/ai/safety/word',
    method: 'put',
    data: data
  });
};

/**
 * 删除敏感词
 * @param id 主键或主键数组
 */
export const delWord = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/safety/word/' + id,
    method: 'delete'
  });
};

/**
 * 查询安全事件列表
 * @param query 查询条件
 */
export const listEvent = (query?: SafetyEventQuery): AxiosPromise<PageResult<SafetyEventVO>> => {
  return request({
    url: '/ai/safety/event/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询安全事件详情
 * @param id 主键
 */
export const queryEvent = (id: string | number): AxiosPromise<SafetyEventVO> => {
  return request({
    url: '/ai/safety/event/' + id,
    method: 'get'
  });
};