import type { RetrievalHitVO, RetrievalQuery } from '@/api/ai/kb/retrieval/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 检索测试：按查询文本召回切片
 * @param query 查询条件
 */
export const testRetrieval = (query: RetrievalQuery): AxiosPromise<RetrievalHitVO[]> => {
  return request({
    url: '/ai/kb/retrieval/test',
    method: 'get',
    params: query
  });
};