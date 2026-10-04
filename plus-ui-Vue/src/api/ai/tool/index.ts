import type { ToolForm, ToolQuery, ToolTagBatchForm, ToolTagForm, ToolTagQuery, ToolTagVO, ToolVO } from '@/api/ai/tool/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询业务工具注册列表
 * @param query 查询条件
 */
export const listTool = (query?: ToolQuery): AxiosPromise<PageResult<ToolVO>> => {
  return request({
    url: '/ai/tool/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询业务工具详情
 * @param id 主键
 */
export const getTool = (id: string | number): AxiosPromise<ToolVO> => {
  return request({
    url: '/ai/tool/' + id,
    method: 'get'
  });
};

/**
 * 新增业务工具注册
 * @param data 表单数据
 */
export const addTool = (data: ToolForm) => {
  return request({
    url: '/ai/tool',
    method: 'post',
    data: data
  });
};

/**
 * 修改业务工具注册（编码不可修改）
 * @param data 表单数据
 */
export const updateTool = (data: ToolForm) => {
  return request({
    url: '/ai/tool',
    method: 'put',
    data: data
  });
};

/**
 * 删除业务工具注册（被智能体绑定时后端拒绝）
 * @param id 主键或主键数组
 */
export const delTool = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/tool/' + id,
    method: 'delete'
  });
};

// ------------------------------------------------------------------
//  工具标签（业务工具注册页内的标签管理）
// ------------------------------------------------------------------

/**
 * 查询标签列表（含使用该标签的工具数）
 * @param query 查询条件
 */
export const listToolTag = (query?: ToolTagQuery): AxiosPromise<PageResult<ToolTagVO>> => {
  return request({
    url: '/ai/tool/tag/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询全部标签选项（含使用计数；供工具列表筛选与工具表单多选）
 */
export const optionToolTag = (): AxiosPromise<ToolTagVO[]> => {
  return request({
    url: '/ai/tool/tag/options',
    method: 'get'
  });
};

/**
 * 新增标签
 * @param data 表单数据
 */
export const addToolTag = (data: ToolTagForm) => {
  return request({
    url: '/ai/tool/tag',
    method: 'post',
    data: data
  });
};

/**
 * 修改标签
 * @param data 表单数据
 */
export const updateToolTag = (data: ToolTagForm) => {
  return request({
    url: '/ai/tool/tag',
    method: 'put',
    data: data
  });
};

/**
 * 删除标签（级联解除其与全部工具的关联）
 * @param id 主键或主键数组
 */
export const delToolTag = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/tool/tag/' + id,
    method: 'delete'
  });
};

/**
 * 批量打标签（追加语义：为勾选的工具追加标签，工具已有标签不受影响）
 * @param data 勾选的工具主键与标签主键
 */
export const applyToolTags = (data: ToolTagBatchForm) => {
  return request({
    url: '/ai/tool/tag/apply',
    method: 'post',
    data: data
  });
};

/**
 * 批量移除标签（从勾选的工具上移除指定标签，工具的其他标签保留）
 * @param data 勾选的工具主键与标签主键
 */
export const removeToolTags = (data: ToolTagBatchForm) => {
  return request({
    url: '/ai/tool/tag/remove',
    method: 'post',
    data: data
  });
};