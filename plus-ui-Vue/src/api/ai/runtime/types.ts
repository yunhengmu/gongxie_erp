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

/** 结构化决策路由配置（docs/21；决策模型连接信息统一由「模型配置」管理，此处只引用模型主键） */
export interface JevConfig {
  /** 总开关：关闭＝零外呼、路由完全由小Z 负责 */
  enabled: boolean;
  /** 所选决策模型（「模型配置」主键，类型 decision）；开启时必填 */
  modelConfigId?: string | number | null;
  /** 判定超时（秒；与「模型配置」的通用超时无关——判定在路由关键路径上，需要更短超时） */
  timeoutSeconds: number;
  /** 执行模式：shadow 影子 / gate 闸门 / preselect 候选预筛 */
  mode: string;
  /** 闸门模式的**基础**置信度阈值（0~1）：候选数 ≤5 档 */
  confidenceThreshold: number;
  /** 闸门模式的基础置信度阈值：候选数 6~10 档 */
  confidenceThresholdMedium: number;
  /** 闸门模式的基础置信度阈值：候选数 >10 档 */
  confidenceThresholdLarge: number;
  /** margin 二次过滤比值 r（top1 / top2；1.0 等价不启用） */
  marginRatio: number;
  /** 决策不可用时是否回落工具决策 */
  failOpen: boolean;
  /** 外呼 state 的字符上限（前置保护） */
  stateMaxChars: number;
  /** 是否落库概率分布 */
  persistProbabilities: boolean;
  /** 是否启用熔断（连续失败达阈值后暂停判定，避免提供方宕机期间每轮都付超时） */
  circuitBreakerEnabled: boolean;
  /** 熔断触发阈值：连续失败次数 */
  circuitBreakerThreshold: number;
  /** 熔断暂停时长（秒） */
  circuitBreakerPauseSeconds: number;
  /** 粘性路由开关：追问场景跳过判定、直接复用上轮专家（docs/22 §3.3 F） */
  stickyRoutingEnabled: boolean;
  /** 粘性路由触发的上轮最低置信度 */
  stickyMinConfidence: number;
  /** 粘性路由触发的本轮最大字数 */
  stickyMaxQueryLength: number;
}