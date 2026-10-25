import type {
  ScenarioPackForm,
  ScenarioPackMcpOptionVO,
  ScenarioPackOptionVO,
  ScenarioPackQuery,
  ScenarioPackVO
} from '@/api/ai/pack/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询场景包列表
 * @param query 查询条件
 */
export const listScenarioPack = (query?: ScenarioPackQuery): AxiosPromise<PageResult<ScenarioPackVO>> => {
  return request({
    url: '/ai/pack/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询可选场景包选项（创建任务时选择；按当前用户生效范围过滤）
 *
 * <p>下拉选项接口：权限不足时不弹全局提示，由调用方置空对应下拉并降级提示。</p>
 */
export const scenarioPackOptions = (): AxiosPromise<ScenarioPackOptionVO[]> => {
  return request({
    url: '/ai/pack/options',
    method: 'get',
    headers: { silentForbidden: true }
  });
};

/**
 * 查询外部 MCP 工具选项（场景包管理页勾选用）
 *
 * <p>下拉选项接口：权限不足时不弹全局提示，由调用方置空对应下拉并降级提示。</p>
 */
export const scenarioPackMcpOptions = (): AxiosPromise<ScenarioPackMcpOptionVO[]> => {
  return request({
    url: '/ai/pack/mcp-options',
    method: 'get',
    headers: { silentForbidden: true }
  });
};

/**
 * 查询场景包详情
 * @param id 主键
 */
export const getScenarioPack = (id: string | number): AxiosPromise<ScenarioPackVO> => {
  return request({
    url: '/ai/pack/' + id,
    method: 'get'
  });
};

/**
 * 新增场景包
 * @param data 表单数据
 */
export const addScenarioPack = (data: ScenarioPackForm) => {
  return request({
    url: '/ai/pack',
    method: 'post',
    data: data
  });
};

/**
 * 修改场景包（编码不可修改；三类绑定集合全量覆盖）
 * @param data 表单数据
 */
export const updateScenarioPack = (data: ScenarioPackForm) => {
  return request({
    url: '/ai/pack',
    method: 'put',
    data: data
  });
};

/**
 * 删除场景包（逻辑删除；历史会话保留快照）
 * @param id 主键或主键数组
 */
export const delScenarioPack = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/pack/' + id,
    method: 'delete'
  });
};