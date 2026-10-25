/**
 * MCP 服务端（出口）类型定义（REQ-AI-15）
 */

/** MCP 出口状态 */
export interface McpServerStatus {
  /** 出口端点路径 */
  endpoint: string;
  /** 是否启用访问口令校验 */
  authEnabled: boolean;
  /** 访问口令 */
  token: string;
  /** 当前出口实际挂载的工具名 */
  liveTools: string[];
}

/** MCP 出口访问配置保存参数 */
export interface McpServerConfigForm {
  /** 是否启用访问口令校验 */
  authEnabled: boolean;
  /** 访问口令 */
  token?: string;
}

/** MCP 出口候选工具 */
export interface McpServerTool {
  /** 工具主键 */
  id: string | number;
  /** 工具编码（即出口上的工具名） */
  toolCode: string;
  /** 工具显示名 */
  toolName: string;
  /** 用途说明 */
  description: string;
  /** 工具类型（0只读 1写入） */
  toolType: string;
  /** 是否已对外暴露 */
  exposed: boolean;
  /** 代码侧实现是否已登记 */
  registered: boolean;
}