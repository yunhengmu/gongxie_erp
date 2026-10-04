/**
 * MCP 客户端（外部 MCP Server 连接）类型定义（REQ-AI-16）
 */

/** 外部 MCP 连接 */
export interface McpClientVO {
  /** 主键 */
  id: string | number;
  /** Server 名称 */
  serverName: string;
  /** 传输方式（sse / http） */
  transport: string;
  /** Server 地址 */
  url: string;
  /** 工具白名单（逗号分隔；为空表示放行全部工具） */
  enableTools?: string;
  /** 单次请求超时（毫秒） */
  timeoutMs?: number;
  /** 初始化超时（毫秒） */
  initTimeoutMs?: number;
  /** 用途说明 */
  description?: string;
  /** 状态（0启用 1停用） */
  status: string;
  /** 备注 */
  remark?: string;
  /** 是否已配置请求头（凭据不回显） */
  headersConfigured: boolean;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

/** 外部 MCP 连接表单 */
export interface McpClientForm {
  id?: string | number;
  serverName?: string;
  transport?: string;
  url?: string;
  /** 请求头 JSON（明文；留空表示保持不变） */
  headersJson?: string;
  /** 工具白名单（逗号分隔） */
  enableTools?: string;
  timeoutMs?: number;
  initTimeoutMs?: number;
  description?: string;
  status?: string;
  remark?: string;
}

/** 外部 MCP 连接查询条件 */
export interface McpClientQuery {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  transport?: string;
  status?: string;
}

/** 连接测试结果 */
export interface McpClientTestResult {
  /** 是否连接成功 */
  success: boolean;
  /** 结果说明 */
  message: string;
  /** Server 名称 */
  serverName?: string;
  /** 该 Server 声明的工具名 */
  tools: string[];
}