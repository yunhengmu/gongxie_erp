/**
 * AI 场景包类型定义（场景包管理页 + 创建任务选包）
 */

/** 包内业务工具绑定（含授权决策） */
export interface ScenarioPackToolVO {
  /** 工具编码 */
  toolCode: string;
  /** 工具名称（后端填充） */
  toolName?: string;
  /** 工具类型（0只读 1写入；后端填充） */
  toolType?: string;
  /** 授权决策（0允许 1需确认 2禁止） */
  decision?: string;
}

/** 包内外部 MCP 工具绑定（含授权决策） */
export interface ScenarioPackMcpToolVO {
  /** MCP 连接主键 */
  mcpClientId: string | number;
  /** Server 名称（后端填充） */
  serverName?: string;
  /** 远端自声明的工具名 */
  toolName: string;
  /** 授权决策（0允许 1需确认 2禁止） */
  decision?: string;
}

export interface ScenarioPackVO extends BaseEntity {
  /** 主键（雪花 ID，前端按字符串处理） */
  id?: string | number;
  /** 场景包编码（唯一，创建后不可修改） */
  packCode?: string;
  /** 场景包名称（如「财务场景包」「人力场景包」） */
  packName?: string;
  /** 用途说明 */
  description?: string;
  /** 生效范围（0公开 1本部门 2仅本人） */
  visibleScope?: string;
  /** 归属部门主键 */
  ownerDeptId?: string | number | null;
  /** 归属部门名称（后端填充） */
  ownerDeptName?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 创建人昵称（后端填充） */
  createByName?: string;
  /** 包内业务工具及授权决策 */
  toolBindings?: ScenarioPackToolVO[];
  /** 包内外部 MCP 工具及授权决策 */
  mcpToolBindings?: ScenarioPackMcpToolVO[];
  /** 包内技能主键集合 */
  skillIds?: Array<string | number>;
  /** 包内技能名称集合（后端填充） */
  skillNames?: string[];
}

export interface ScenarioPackForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 场景包编码 */
  packCode?: string;
  /** 场景包名称 */
  packName?: string;
  /** 用途说明 */
  description?: string;
  /** 生效范围（0公开 1本部门 2仅本人） */
  visibleScope?: string;
  /** 归属部门主键（visibleScope=1 时落库） */
  ownerDeptId?: string | number | null;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 包内业务工具及授权决策 */
  toolBindings?: ScenarioPackToolVO[];
  /** 包内外部 MCP 工具及授权决策 */
  mcpToolBindings?: ScenarioPackMcpToolVO[];
  /** 包内技能主键集合 */
  skillIds?: Array<string | number>;
}

export interface ScenarioPackQuery extends PageQuery {
  /** 编码 / 名称 / 用途说明模糊匹配 */
  keyword?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 生效范围（0公开 1本部门 2仅本人） */
  visibleScope?: string;
}

/** 创建任务时的可选场景包（按当前用户生效范围过滤） */
export interface ScenarioPackOptionVO {
  /** 主键 */
  id: string | number;
  /** 场景包编码 */
  packCode: string;
  /** 场景包名称 */
  packName: string;
  /** 用途说明（供用户判断该选哪个包） */
  description?: string;
  /** 包内业务工具数量 */
  toolCount?: number;
  /** 包内外部 MCP 工具数量 */
  mcpToolCount?: number;
  /** 包内技能数量 */
  skillCount?: number;
}

/** 外部 MCP 工具选项（启用中的连接 + 其工具白名单） */
export interface ScenarioPackMcpOptionVO {
  /** MCP 连接主键 */
  mcpClientId: string | number;
  /** Server 名称 */
  serverName: string;
  /** 该连接的工具白名单（为空表示未配置，无可选项） */
  tools?: string[];
}