import type { KnowledgeBaseForm, KnowledgeBaseQuery, KnowledgeBaseVO } from '@/api/ai/kb/base/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询知识库列表
 * @param query 查询条件
 */
export const listKnowledgeBase = (query?: KnowledgeBaseQuery): AxiosPromise<PageResult<KnowledgeBaseVO>> => {
  return request({
    url: '/ai/kb/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询启用中的知识库选项（管理视角，全量）
 */
export const optionKnowledgeBase = (): AxiosPromise<KnowledgeBaseVO[]> => {
  return request({
    url: '/ai/kb/options',
    method: 'get'
  });
};

/**
 * 查询当前用户可见且启用的知识库选项（智能体绑定、检索测试用）
 */
export const visibleKnowledgeBaseOptions = (): AxiosPromise<KnowledgeBaseVO[]> => {
  return request({
    url: '/ai/kb/visible-options',
    method: 'get'
  });
};

/**
 * 查询知识库详情
 * @param id 主键
 */
export const getKnowledgeBase = (id: string | number): AxiosPromise<KnowledgeBaseVO> => {
  return request({
    url: '/ai/kb/' + id,
    method: 'get'
  });
};

/**
 * 新增知识库
 * @param data 表单数据
 */
export const addKnowledgeBase = (data: KnowledgeBaseForm) => {
  return request({
    url: '/ai/kb',
    method: 'post',
    data: data
  });
};

/**
 * 修改知识库
 * @param data 表单数据
 */
export const updateKnowledgeBase = (data: KnowledgeBaseForm) => {
  return request({
    url: '/ai/kb',
    method: 'put',
    data: data
  });
};

/**
 * 重建知识库 BM25 全文索引（切换为混合检索或索引失败后手动触发）
 * @param id 知识库主键
 */
export const rebuildBm25Index = (id: string | number) => {
  return request({
    url: '/ai/kb/' + id + '/bm25/rebuild',
    method: 'post'
  });
};

/**
 * 删除知识库
 * @param id 主键或主键数组
 */
export const delKnowledgeBase = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/kb/' + id,
    method: 'delete'
  });
};