import type { SkillForm, SkillOptionVO, SkillQuery, SkillVO } from '@/api/ai/skill/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询技能列表（管理页全量，含使用计数与陈旧归档状态）
 * @param query 查询条件
 */
export const listSkill = (query?: SkillQuery): AxiosPromise<PageResult<SkillVO>> => {
  return request({
    url: '/ai/skill/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询当前用户可见的启用中技能选项（供智能体定义页绑定技能）
 *
 * <p>下拉选项接口：权限不足时不弹全局提示，由调用方置空对应下拉并降级提示。</p>
 */
export const visibleSkillOptions = (): AxiosPromise<SkillOptionVO[]> => {
  return request({
    url: '/ai/skill/visible-options',
    method: 'get',
    headers: { silentForbidden: true }
  });
};

/**
 * 查询技能详情（含参考资料明细）
 * @param id 主键
 */
export const getSkill = (id: string | number): AxiosPromise<SkillVO> => {
  return request({
    url: '/ai/skill/' + id,
    method: 'get'
  });
};

/**
 * 新增技能
 * @param data 表单数据
 */
export const addSkill = (data: SkillForm) => {
  return request({
    url: '/ai/skill',
    method: 'post',
    data: data
  });
};

/**
 * 修改技能（技能标识不可修改）
 * @param data 表单数据
 */
export const updateSkill = (data: SkillForm) => {
  return request({
    url: '/ai/skill',
    method: 'put',
    data: data
  });
};

/**
 * 删除技能（物理删除，被智能体绑定时后端拒绝）
 * @param id 主键或主键数组
 */
export const delSkill = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/skill/' + id,
    method: 'delete'
  });
};
