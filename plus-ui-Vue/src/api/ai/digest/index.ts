import type { AiDailyDigestQuery, AiDailyDigestVO } from '@/api/ai/digest/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询平台运行日报列表
 * @param query 查询条件
 */
export const listDailyDigest = (query?: AiDailyDigestQuery): AxiosPromise<PageResult<AiDailyDigestVO>> => {
  return request({
    url: '/ai/digest/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询指定日期的平台运行日报详情
 * @param digestDate 日报日期（yyyy-MM-dd）
 */
export const getDailyDigest = (digestDate: string): AxiosPromise<AiDailyDigestVO> => {
  return request({
    url: `/ai/digest/${digestDate}`,
    method: 'get'
  });
};
