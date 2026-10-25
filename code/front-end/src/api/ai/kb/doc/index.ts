import type {
  ChunkContextVO,
  ChunkVO,
  DocumentBatchStatusVO,
  DocumentBatchVO,
  DocumentForm,
  DocumentQuery,
  DocumentVO
} from '@/api/ai/kb/doc/types';
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
 * 按切片序号定位切片（回答「参考来源」点击用）
 * @param id 文档主键
 * @param chunkIndex 切片序号（从 0 开始）
 * @param radius 前后各取多少条相邻切片（后端默认 1，上限 3）
 */
export const getDocumentChunkContext = (
  id: string | number,
  chunkIndex: number,
  radius?: number
): AxiosPromise<ChunkContextVO> => {
  return request({
    url: '/ai/kb/doc/' + id + '/chunks/' + chunkIndex,
    method: 'get',
    params: radius === undefined ? undefined : { radius }
  });
};

/**
 * 登记文档（文件已上传至对象存储）
 * @param data 表单数据
 * @returns 新文档主键（上传后自动入库需要用到）
 */
export const addDocument = (data: DocumentForm): AxiosPromise<string | number> => {
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
 * 批量解析（异步受理，进度需轮询 getBatchStatus）
 * @param ids 文档主键数组
 */
export const batchParseDocument = (ids: Array<string | number>): AxiosPromise<DocumentBatchVO> => {
  return request({
    url: '/ai/kb/doc/batch/parse',
    method: 'post',
    data: { ids }
  });
};

/**
 * 批量向量化（异步受理）
 * @param ids 文档主键数组
 */
export const batchVectorizeDocument = (ids: Array<string | number>): AxiosPromise<DocumentBatchVO> => {
  return request({
    url: '/ai/kb/doc/batch/vectorize',
    method: 'post',
    data: { ids }
  });
};

/**
 * 批量一键入库（异步受理：先解析，成功后串接向量化）
 * @param ids 文档主键数组
 */
export const batchIngestDocument = (ids: Array<string | number>): AxiosPromise<DocumentBatchVO> => {
  return request({
    url: '/ai/kb/doc/batch/ingest',
    method: 'post',
    data: { ids }
  });
};

/**
 * 查询批量处理进度（轻量回执）
 * @param ids 文档主键数组
 */
export const getBatchStatus = (ids: Array<string | number>): AxiosPromise<DocumentBatchStatusVO[]> => {
  return request({
    url: '/ai/kb/doc/batch/status',
    method: 'get',
    params: { ids: ids.join(',') }
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