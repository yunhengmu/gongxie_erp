import type {
  AgentForm,
  AgentQuery,
  AgentToolTrustVO,
  AgentVO,
  EntryAgentForm,
  ExpertOptionVO,
  ExpertPromptGenerateForm,
  ExpertPromptGenerateVO,
  ToolOptionVO
} from '@/api/ai/agent/types';
import type { PageResult } from '@/api/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询智能体定义列表
 * @param query 查询条件
 */
export const listAgent = (query?: AgentQuery): AxiosPromise<PageResult<AgentVO>> => {
  return request({
    url: '/ai/agent/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询入口智能体（小Z，全平台唯一）
 */
export const getEntryAgent = (): AxiosPromise<AgentVO> => {
  return request({
    url: '/ai/agent/entry',
    method: 'get'
  });
};

/**
 * 修改入口智能体（小Z；只读写公共字段）
 * @param data 表单数据（仅公共字段）
 */
export const updateEntryAgent = (data: EntryAgentForm) => {
  return request({
    url: '/ai/agent/entry',
    method: 'put',
    data: data
  });
};

/**
 * 查询启用中的智能体选项（入口 + 全部启用专家，供对话工作台选择）
 */
export const optionAgent = (): AxiosPromise<AgentVO[]> => {
  return request({
    url: '/ai/agent/options',
    method: 'get'
  });
};

/**
 * 查询当前用户可见的**全部**专家清单（供对话页「指定专家」下拉，REQ-AI-31/33）
 *
 * <p>不过滤「自动路由」：关闭自动路由的专家只能被用户手动指定，故仍需出现在该下拉。</p>
 */
export const optionAgentExperts = (): AxiosPromise<ExpertOptionVO[]> => {
  return request({
    url: '/ai/agent/experts',
    method: 'get'
  });
};

/**
 * 查询当前用户可见的**自动候选**专家清单（管理面核对路由候选，REQ-AI-33）
 */
export const optionAgentExpertCandidates = (): AxiosPromise<ExpertOptionVO[]> => {
  return request({
    url: '/ai/agent/experts/candidates',
    method: 'get'
  });
};

/**
 * 查询内置业务工具选项
 */
export const optionAgentTool = (): AxiosPromise<ToolOptionVO[]> => {
  return request({
    url: '/ai/agent/tool/options',
    method: 'get'
  });
};

/**
 * 查询智能体定义详情
 * @param id 主键
 */
export const getAgent = (id: string | number): AxiosPromise<AgentVO> => {
  return request({
    url: '/ai/agent/' + id,
    method: 'get'
  });
};

/**
 * 按少量硬信息辅助生成专家配置建议值（描述 / 能力描述 / 系统提示词）
 * @param data 硬信息
 */
export const generateExpertPrompt = (data: ExpertPromptGenerateForm): AxiosPromise<ExpertPromptGenerateVO> => {
  return request({
    url: '/ai/agent/experts/prompt/generate',
    method: 'post',
    data
  });
};

/**
 * 新增智能体定义
 * @param data 表单数据
 */
export const addAgent = (data: AgentForm) => {
  return request({
    url: '/ai/agent',
    method: 'post',
    data: data
  });
};

/**
 * 修改智能体定义
 * @param data 表单数据
 */
export const updateAgent = (data: AgentForm) => {
  return request({
    url: '/ai/agent',
    method: 'put',
    data: data
  });
};

/**
 * 删除智能体定义
 * @param id 主键或主键数组
 */
export const delAgent = (id: string | number | Array<string | number>) => {
  return request({
    url: '/ai/agent/' + id,
    method: 'delete'
  });
};

/**
 * 查询该智能体已记住的工具（「记住该工具」沉淀的免确认决策）
 * @param agentId 智能体主键
 */
export const listTrustedTools = (agentId: string | number): AxiosPromise<AgentToolTrustVO[]> => {
  return request({
    url: `/ai/agent/${agentId}/trusted-tools`,
    method: 'get'
  });
};

/**
 * 撤销某条「记住该工具」信任决策（按用户维度：只影响该用户）
 * @param agentId 智能体主键
 * @param userId 记住该工具的用户主键
 * @param toolCode 工具编码
 */
export const revokeTrustedTool = (agentId: string | number, userId: string | number, toolCode: string) => {
  return request({
    url: `/ai/agent/${agentId}/trusted-tools`,
    method: 'delete',
    params: { userId, toolCode }
  });
};
