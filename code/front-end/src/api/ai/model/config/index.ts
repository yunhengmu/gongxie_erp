import type { ModelConfigForm, ModelConfigQuery, ModelConfigVO, ModelTestResult } from '@/api/ai/model/config/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询模型配置列表
 * @param query 查询条件
 */
export const listModelConfig = (query?: ModelConfigQuery): AxiosPromise<PageResult<ModelConfigVO>> => {
  return request({
    url: '/ai/model/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询启用中的模型选项
 *
 * <p>下拉选项接口（智能体定义页 / 知识库页 / 子智能体页共用）：权限不足时不弹全局提示，
 * 由调用方置空对应下拉并降级提示。</p>
 *
 * @param modelType 模型类型，为空表示不限
 */
export const optionModelConfig = (modelType?: string): AxiosPromise<ModelConfigVO[]> => {
  return request({
    url: '/ai/model/options',
    method: 'get',
    params: { modelType },
    headers: { silentForbidden: true }
  });
};

/**
 * 查询模型配置详情
 * @param id 主键
 */
export const getModelConfig = (id: string | number): AxiosPromise<ModelConfigVO> => {
  return request({
    url: '/ai/model/' + id,
    method: 'get'
  });
};

/**
 * 新增模型配置
 * @param data 表单数据
 */
export const addModelConfig = (data: ModelConfigForm) => {
  return request({
    url: '/ai/model',
    method: 'post',
    data: data
  });
};

/**
 * 复制模型配置（按源配置整行复制：密钥密文与状态原样复制，名称自动追加 copy）
 * @param id 源配置主键
 */
export const copyModelConfig = (id: string | number): AxiosPromise<ModelConfigVO> => {
  return request({
    url: '/ai/model/copy/' + id,
    method: 'post'
  });
};

/**
 * 修改模型配置
 * @param data 表单数据
 */
export const updateModelConfig = (data: ModelConfigForm) => {
  return request({
    url: '/ai/model',
    method: 'put',
    data: data
  });
};

/**
 * 删除模型配置
 * @param id 主键或主键数组
 */
export const delModelConfig = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/model/' + id,
    method: 'delete'
  });
};

/**
 * 模型连通性测试（不落库）
 * @param data 表单数据，带 id 且未填密钥时复用库中密钥
 */
export const testModelConfig = (data: ModelConfigForm): AxiosPromise<ModelTestResult> => {
  return request({
    url: '/ai/model/test',
    method: 'post',
    data: data
  });
};