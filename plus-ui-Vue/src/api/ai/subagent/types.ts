/**
 * AI 子智能体类型定义（子智能体管理页与智能体绑定）
 */

export interface SubagentVO extends BaseEntity {
  /** 主键（雪花 ID，前端按字符串处理） */
  id?: string | number;
  /** 子智能体标识（唯一） */
  name?: string;
  /** 用途说明 */
  description?: string;
  /** 子智能体系统提示词 */
  sysPrompt?: string;
  /** 工作区模式（ISOLATED 隔离 / SHARED 共享） */
  workspaceMode?: string;
  /** 绑定模型主键 */
  modelId?: string | number | null;
  /** 模型标识（后端填充） */
  modelName?: string;
  /** 单次委派最大推理步数 */
  steps?: number;
  /** 工具白名单（只读工具编码） */
  toolCodeList?: string[];
  /** 工具显示名（后端填充） */
  toolNames?: string[];
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 创建人昵称 */
  createByName?: string;
}

export interface SubagentOptionVO {
  /** 主键 */
  id: string | number;
  /** 子智能体标识 */
  name: string;
  /** 用途说明 */
  description?: string;
  /** 工作区模式 */
  workspaceMode?: string;
  /** 单次委派最大推理步数 */
  steps?: number;
  /** 工具白名单（只读工具编码） */
  toolCodeList?: string[];
}

export interface SubagentForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 子智能体标识 */
  name?: string;
  /** 用途说明 */
  description?: string;
  /** 子智能体系统提示词 */
  sysPrompt?: string;
  /** 工作区模式（ISOLATED 隔离 / SHARED 共享） */
  workspaceMode?: string;
  /** 绑定模型主键（留空则继承） */
  modelId?: string | number | null;
  /** 单次委派最大推理步数 */
  steps?: number;
  /** 工具白名单（只读工具编码） */
  toolCodeList?: string[];
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
}

export interface SubagentQuery extends PageQuery {
  /** 标识 / 用途说明模糊匹配 */
  keyword?: string;
  /** 状态（0启用 1停用） */
  status?: string;
}