/**
 * AI 智能体定义类型定义
 */

/** 智能体 × 工具绑定（含授权决策） */
export interface AgentToolBinding {
  /** 工具编码 */
  toolCode: string;
  /** 授权决策（0允许 1需人工确认 2禁止） */
  decision?: string;
}

export interface AgentVO {
  /** 主键 */
  id: string | number;
  /** 智能体名称 */
  agentName: string;
  /** 智能体编码（唯一） */
  agentCode: string;
  /** 一句话职责说明 */
  description?: string;
  /** 系统提示词 */
  sysPrompt?: string;
  /** 绑定对话模型主键 */
  modelId: string | number;
  /** 对话模型名称（后端附加字段） */
  modelName?: string;
  /** 长期记忆抽取 / 合并用的小模型（待 2D 生效） */
  memoryModelId?: string | number;
  /** 上下文压缩摘要模型（待 2C 生效） */
  summaryModelId?: string | number;
  /** 子智能体默认模型（待 P3 生效） */
  subagentModelId?: string | number;
  /** 日 Token 配额（0 表示不限） */
  dailyTokenQuota?: number;
  /** 日请求数配额（0 表示不限） */
  dailyRequestQuota?: number;
  /** 月 Token 配额（0 表示不限） */
  monthlyTokenQuota?: number;
  /** 归属部门 ID */
  ownerDeptId?: string | number;
  /** 可见范围（0全员 1本部门 2仅创建人） */
  visibleScope?: string;
  /** 单轮最大迭代次数 */
  maxIters?: number;
  /** 状态（0正常 1停用） */
  status: string;
  /** 备注 */
  remark?: string;
  /** 编排角色（entry 入口 / expert 专家；REQ-AI-33 起由后端按实体类型填充） */
  role?: string;
  /** 专家领域标签（2026-10-02 起已停用；仅历史数据可能非空） */
  expertDomain?: string;
  /** 能力描述（注入入口智能体的路由提示词；为空回落 description） */
  capabilityDesc?: string;
  /** 专家是否允许使用写工具（定义页已移除该开关，恒为 1） */
  expertWriteEnabled?: string;
  /** 是否自动路由（1 进入口智能体的自动候选清单；0 只能被用户手动指定；REQ-AI-33） */
  autoRoute?: string;
  /** 绑定的业务工具及授权决策 */
  toolBindings?: AgentToolBinding[];
  /** 绑定的知识库主键集合 */
  kbIds?: (string | number)[];
  /** 绑定的技能主键集合 */
  skillIds?: (string | number)[];
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface AgentForm extends BaseEntity {
  /** 主键 */
  id?: string | number;
  /** 智能体名称 */
  agentName?: string;
  /** 智能体编码（唯一） */
  agentCode?: string;
  /** 一句话职责说明 */
  description?: string;
  /** 系统提示词（留空时后端落默认提示词） */
  sysPrompt?: string;
  /** 绑定对话模型主键 */
  modelId?: string | number;
  /** 长期记忆抽取 / 合并用的小模型（可空；待 2D 生效） */
  memoryModelId?: string | number;
  /** 上下文压缩摘要模型（可空；待 2C 生效） */
  summaryModelId?: string | number;
  /** 子智能体默认模型（可空；待 P3 生效） */
  subagentModelId?: string | number;
  /** 日 Token 配额（0 表示不限） */
  dailyTokenQuota?: number;
  /** 日请求数配额（0 表示不限） */
  dailyRequestQuota?: number;
  /** 月 Token 配额（0 表示不限） */
  monthlyTokenQuota?: number;
  /** 归属部门 ID */
  ownerDeptId?: string | number;
  /** 可见范围（0全员 1本部门 2仅创建人） */
  visibleScope?: string;
  /** 单轮最大迭代次数 */
  maxIters?: number;
  /** 状态（0正常 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 专家是否允许使用写工具（0 不允许＝强制只读 / 1 允许按「允许」档生效） */
  expertWriteEnabled?: string;
  /** 是否自动路由（1 进入口智能体的自动候选清单 / 0 只能被用户手动指定；留空按开启） */
  autoRoute?: string;
  /** 绑定的业务工具及授权决策 */
  toolBindings?: AgentToolBinding[];
  /** 绑定的知识库主键集合 */
  kbIds?: (string | number)[];
  /** 绑定的技能主键集合 */
  skillIds?: (string | number)[];
}

export interface AgentQuery extends PageQuery {
  /** 名称关键字 */
  nameKeyword?: string;
  /** 智能体编码 */
  agentCode?: string;
  /** 状态 */
  status?: string;
}

/**
 * 入口智能体（小Z）配置表单（REQ-AI-33）。
 *
 * <p>只含公共字段：入口不绑定业务工具 / 知识库 / 技能 / 子智能体（能力由会话资源集提供）。</p>
 */
export interface EntryAgentForm {
  /** 智能体名称 */
  agentName?: string;
  /** 智能体编码（创建后不可修改） */
  agentCode?: string;
  /** 一句话职责说明 */
  description?: string;
  /** 系统提示词（留空时后端落默认提示词） */
  sysPrompt?: string;
  /** 绑定对话模型主键 */
  modelId?: string | number;
  /** 长期记忆抽取 / 合并用的小模型（可空） */
  memoryModelId?: string | number;
  /** 上下文压缩摘要模型（可空） */
  summaryModelId?: string | number;
  /** 子智能体默认模型（可空） */
  subagentModelId?: string | number;
  /** 日 Token 配额（0 表示不限） */
  dailyTokenQuota?: number;
  /** 日请求数配额（0 表示不限） */
  dailyRequestQuota?: number;
  /** 月 Token 配额（0 表示不限） */
  monthlyTokenQuota?: number;
  /** 归属部门 ID */
  ownerDeptId?: string | number;
  /** 可见范围（0全员 1本部门 2仅创建人） */
  visibleScope?: string;
  /** 单轮最大迭代次数 */
  maxIters?: number;
  /** 状态（0正常 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
}

/** 候选专家（REQ-AI-31：对话页「指定专家」下拉，与编排候选清单同源） */
export interface ExpertOptionVO {
  /** 专家智能体主键（指定专家时提交该值） */
  agentId: string | number;
  /** 专家标识（ai_agent.agent_code） */
  agentKey?: string;
  /** 专家名称 */
  agentName?: string;
  /** 领域标签 */
  expertDomain?: string;
  /** 能力描述（为空回落 description） */
  capabilityDesc?: string;
  /** 是否自动路由（1 自动候选 / 0 仅手动指定；REQ-AI-33） */
  autoRoute?: string;
}

export interface ToolOptionVO {
  /** 主键 */
  id?: string | number;
  /** 工具编码 */
  toolCode: string;
  /** 工具显示名 */
  toolName: string;
  /** 工具类型（0 只读 / 1 写入） */
  toolType?: string;
  /** 是否只读工具 */
  readOnly?: boolean;
  /** 工具用途说明 */
  description?: string;
}

/** 「记住该工具」信任决策（对话确认卡片沉淀的免确认决策，按用户区分） */
export interface AgentToolTrustVO {
  /** 工具编码（业务工具编码或外部 MCP 工具名） */
  toolCode: string;
  /** 记住该工具的用户主键 */
  userId?: string | number;
  /** 记住该工具的用户昵称 */
  userName?: string;
  /** 信任决策（0允许 1需确认 2禁止） */
  decision?: string;
  /** 决策来源（remember / manual） */
  source?: string;
  /** 形成时间 */
  createTime?: string;
}

/** 专家提示词辅助生成入参（只收集少量硬信息） */
export interface ExpertPromptGenerateForm {
  /** 专家名称 */
  agentName?: string;
  /** 领域一句话 */
  domainHint?: string;
  /** 将绑定的业务工具显示名 */
  toolNames?: string[];
  /** 其中属于写操作的工具显示名 */
  writeToolNames?: string[];
  /** 可检索的知识库名称 */
  kbNames?: string[];
  /** 是否允许写操作 */
  writeEnabled?: boolean;
  /** 用于生成的对话模型主键（可空，取首个启用对话模型） */
  modelId?: string | number;
}

/** 专家提示词辅助生成结果（建议值，回填后仍可编辑） */
export interface ExpertPromptGenerateVO {
  /** 是否生成成功 */
  success?: boolean;
  /** 结果说明（成功为提示，失败为原因） */
  message?: string;
  /** 一句话职责说明（建议值；同时作为路由候选清单里的「擅长」） */
  description?: string;
  /** 系统提示词（建议值） */
  sysPrompt?: string;
  /** 实际使用的模型名称 */
  modelName?: string;
  /** 耗时（毫秒） */
  elapsedMs?: number;
}
