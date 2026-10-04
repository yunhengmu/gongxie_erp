import type { ChunkVO, DocumentForm, DocumentQuery, DocumentVO } from '@/api/ai/kb/doc/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询文档列表
 * @param query 查询条件
 */
export const listDocument = (query?: DocumentQuery): AxiosPromise<PageResult<DocumentVO>> => {
  return request({
    url: '/ai/kb/doc/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询文档解析后的纯文本（在线预览）
 * @param id 主键
 */
export const getDocumentContent = (id: string | number): AxiosPromise<string> => {
  return request({
    url: '/ai/kb/doc/' + id + '/content',
    method: 'get'
  });
};

/**
 * 分页查询文档切片
 * @param id 主键
 * @param query 分页参数
 */
export const listDocumentChunk = (id: string | number, query?: PageQuery): AxiosPromise<PageResult<ChunkVO>> => {
  return request({
    url: '/ai/kb/doc/' + id + '/chunks',
    method: 'get',
    params: query
  });
};

/**
 * 登记文档（文件已上传至对象存储）
 * @param data 表单数据
 */
export const addDocument = (data: DocumentForm) => {
  return request({
    url: '/ai/kb/doc',
    method: 'post',
    data: data
  });
};

/**
 * 解析文档（提取文本 + 重建切片）
 * @param id 主键
 */
export const parseDocument = (id: string | number): AxiosPromise<DocumentVO> => {
  return request({
    url: '/ai/kb/doc/' + id + '/parse',
    method: 'post'
  });
};

/**
 * 向量化文档（切片写入向量库，重复执行即重建索引）
 * @param id 主键
 */
export const vectorizeDocument = (id: string | number): AxiosPromise<DocumentVO> => {
  return request({
    url: '/ai/kb/doc/' + id + '/vectorize',
    method: 'post'
  });
};

/**
 * 删除文档
 * @param id 主键或主键数组
 */
export const delDocument = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/kb/doc/' + id,
    method: 'delete'
  });
};