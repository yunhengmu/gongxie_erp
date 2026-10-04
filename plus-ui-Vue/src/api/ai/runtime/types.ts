/**
 * AI 运行期策略类型定义（REQ-AI-21 上下文压缩 / REQ-AI-22 计划模式与任务清单）
 */

/** 上下文压缩与大工具结果卸载策略 */
export interface CompactionConfig {
  /** 是否启用上下文压缩 */
  enabled: boolean;
  /** 触发压缩的消息条数阈值 */
  triggerMessages: number;
  /** 触发压缩的 Token 数阈值 */
  triggerTokens: number;
  /** 压缩后保留的尾部消息条数 */
  keepMessages: number;
  /** 压缩前是否将完整历史落盘留痕 */
  flushBeforeCompact: boolean;
  /** 压缩前是否把历史卸载为工作区下的 jsonl 文件 */
  offloadBeforeCompact: boolean;
  /** 是否启用大工具结果卸载 */
  toolResultEvictionEnabled: boolean;
  /** 大工具结果卸载阈值（字符） */
  toolResultEvictionMaxChars: number;
  /** 大工具结果卸载后保留的预览字符数（首尾各一份） */
  toolResultEvictionPreviewChars: number;
}

/** 计划模式与任务清单配置 */
export interface PlanConfig {
  /** 是否启用计划模式 */
  enabled: boolean;
  /** 计划文件目录（相对智能体工作区） */
  planFileDirectory: string;
  /** 是否启用任务清单（todo_write） */
  taskListEnabled: boolean;
}