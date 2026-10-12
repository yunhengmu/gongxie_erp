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

/** 编排预算配置（REQ-AI-30；0 表示不限） */
export interface RouteBudgetConfig {
  /** 单轮编排的专家轮 Token 预算（专家调用输入 + 输出累计），0 表示不限 */
  expertTokens: number;
  /** 单轮编排的墙钟预算（秒），0 表示不限 */
  wallSeconds: number;
}

/** 安全合规配置（运行期安全策略） */
export interface SecurityConfig {
  /** 是否启用安全合规（总开关，关闭后所有场景检测与拦截全部停用） */
  enabled: boolean;
  /** 检测引擎（local 本地词库 / external 外部 API） */
  engine: 'local' | 'external';
  /** 命中动作（log 只记录 / mask 脱敏放行 / block 阻断） */
  action: 'log' | 'mask' | 'block';
  /** 是否检测用户输入 */
  inputCheck: boolean;
  /** 是否检测模型输出 */
  outputCheck: boolean;
  /** 是否检测渠道出站内容 */
  channelOutCheck: boolean;
  /** 是否审核工具结果：落库留痕与前端展示 */
  toolResultMask: boolean;
  /** 是否审核知识库入库内容，命中阻断则拒绝入库 */
  kbIngestCheck: boolean;
  /** 是否审核 MCP 出口入参 / 出参 */
  mcpOutCheck: boolean;
  /** 是否启用提示词注入防护：系统提示加固 + 外部内容边界标记 */
  promptHardening: boolean;
  /** 是否对 PII（手机号 / 身份证 / 邮箱 / 银行卡 / IP）脱敏 */
  maskPii: boolean;
  /** 流式输出回退字符数（先缓冲 N 个字符再判定），0 表示不缓冲 */
  holdBackChars: number;
  /** 安全事件中保留的命中原文片段字符数 */
  excerptChars: number;
  /** 外部引擎接口地址（engine=external 时使用） */
  providerEndpoint: string;
  /** 外部引擎接口密钥（engine=external 时使用） */
  providerApiKey: string;
}

/** 结构化决策路由配置（docs/21；服务端不回显密钥，仅返回是否已配置） */
export interface JevConfig {
  /** 总开关：关闭＝零外呼、路由完全由小Z 负责 */
  enabled: boolean;
  /** 决策模型服务地址（百炼 compatible-mode 基址） */
  baseUrl: string;
  /** 决策模型标识 */
  model: string;
  /** 外呼超时（秒） */
  timeoutSeconds: number;
  /** 执行模式：shadow 影子 / gate 闸门 / preselect 候选预筛 */
  mode: string;
  /** 闸门模式的置信度阈值（0~1） */
  confidenceThreshold: number;
  /** 决策不可用时是否回落工具决策 */
  failOpen: boolean;
  /** 外呼 state 的字符上限（前置保护） */
  stateMaxChars: number;
  /** 是否落库概率分布 */
  persistProbabilities: boolean;
  /** 是否已配置密钥（服务端仅返回该布尔值，不回显密钥明文） */
  apiKeyConfigured?: boolean;
  /** 待保存的密钥：留空表示不修改已保存密钥（仅提交用，读取时无此字段） */
  apiKey?: string;
}