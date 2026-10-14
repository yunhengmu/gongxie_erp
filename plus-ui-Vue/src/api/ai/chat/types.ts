/**
 * AI 对话工作台类型定义
 */

export interface ChatSessionVO {
  /** 会话主键（即 AgentScope 的 sessionId） */
  id: string | number;
  /** 会话标题 */
  sessionTitle?: string;
  /** 所属智能体主键 */
  agentId: string | number;
  /** 智能体编码快照 */
  agentCode?: string;
  /** 所选场景包主键（可空；为空表示该会话不接入任何 MCP 工具） */
  scenarioPackId?: string | number | null;
  /** 所选场景包编码快照（可空） */
  scenarioPackCode?: string;
  /** 所选场景包名称（后端填充，可空） */
  scenarioPackName?: string;
  /** 指定专家主键（可空；REQ-AI-31：非空表示本会话强制路由到该专家） */
  pinnedExpertId?: string | number | null;
  /** 指定专家名称（后端填充，可空） */
  pinnedExpertName?: string;
  /** 归属用户 ID */
  userId?: string | number;
  /** 归属部门 ID */
  deptId?: string | number;
  /** 消息条数 */
  messageCount?: number;
  /** 状态（0进行中 1已结束） */
  status?: string;
  /** 置顶标志（0普通 1置顶） */
  topFlag?: string;
  /** 最后活跃时间 */
  lastActiveTime?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface ChatMessageVO {
  /** 主键 */
  id: string | number;
  /** 所属会话主键 */
  sessionId: string | number;
  /** 角色（user / assistant / system） */
  role: 'user' | 'assistant' | 'system';
  /** 消息正文 */
  content: string;
  /** AgentScope replyId（聚合流式块） */
  replyId?: string;
  /** 是否流式中（0已完成 1流式中） */
  streaming?: string;
  /** 状态（0正常 1失败） */
  status?: string;
  /** 失败原因 */
  errorMsg?: string;
  /** 工具调用次数 */
  toolCallCount?: number;
  /** 总 Token 数 */
  totalTokens?: number;
  /** 耗时（毫秒） */
  durationMs?: number;
  /** 创建时间 */
  createTime?: string;
  /** 随本条消息发送的附件（聊天上传的图片/文档） */
  attachments?: ChatAttachmentVO[];
  /** 回答参考来源（JSON 字符串，前端自行解析；知识库命中时非空） */
  sources?: string;
}

/** 回答参考来源（知识库命中：文档名 + 章节 / 页码 + 相似度） */
export interface ChatSourceItem {
  /** 来源文档主键 */
  docId?: string | number;
  /** 来源文档名称 */
  docName?: string;
  /** 章节路径（各级标题用 > 连接，可空） */
  headingPath?: string;
  /** 切片起始页码（可空） */
  pageNo?: number;
  /** 切片序号（可空） */
  chunkIndex?: number;
  /** 相似度得分（可空） */
  score?: number;
}

/** 聊天附件（图片/文档） */
export interface ChatAttachmentVO {
  /** 附件主键 */
  id: string | number;
  /** 附件类型（image 图片 / document 文档） */
  fileType: 'image' | 'document';
  /** 原始文件名 */
  fileName: string;
  /** 文件后缀（小写，不含点） */
  fileSuffix?: string;
  /** 文件大小（字节） */
  fileSize?: number;
  /** 访问地址（图片预览 / 文档下载） */
  url?: string;
  /** 文档解析后的字符数 */
  parsedCharCount?: number;
}

/** 聊天附件登记参数 */
export interface ChatAttachmentBo {
  /** 对象存储主键（框架上传返回） */
  ossId: string | number;
  /** 原始文件名 */
  fileName: string;
}

export interface ChatSessionQuery extends PageQuery {
  /** 会话标题（模糊匹配） */
  sessionTitle?: string;
  /** 所属智能体主键 */
  agentId?: string | number;
  /** 状态（0进行中 1已结束） */
  status?: string;
}

/** 会话级资源选项类型（REQ-AI-32） */
export type ChatResourceType = 'scenario' | 'tool' | 'skill' | 'mcp_tool';

/** 会话级资源项（GET 返回，含展示名，用于标签 chip 渲染） */
export interface ChatResourceVO {
  /** 主键 */
  id?: string | number;
  /** 资源类型 */
  resourceType: ChatResourceType;
  /** 资源主键（MCP 工具为 MCP 连接主键） */
  resourceId: string | number;
  /** 稳定键（场景包编码 / 工具编码 / 技能名 / MCP 远端工具名） */
  resourceKey?: string;
  /** 展示名 */
  displayName?: string;
  /** 副标签 */
  subLabel?: string;
  /** 工具类型（0 只读 / 1 写入；仅 tool 有值） */
  toolType?: string;
}

/** 会话级资源批量增 / 删的单项（REQ-AI-32） */
export interface ChatResourceItem {
  /** 资源类型 */
  resourceType: ChatResourceType;
  /** 资源主键 */
  resourceId: string | number;
  /** 稳定键（MCP 工具必填：远端工具名） */
  resourceKey?: string;
}

/**
 * SSE 事件信封（无名消息，data 为 JSON 字符串，按 type 判别）
 */
export interface ChatStreamEvent {
  /** 事件类型 */
  type:
    | 'start'
    | 'text_delta'
    | 'tool_call_start'
    | 'tool_call_delta'
    | 'tool_result'
    | 'tool_confirm'
    | 'route_decision'
    | 'expert_start'
    | 'expert_result'
    | 'expert_invalid'
    | 'route_fallback'
    | 'answer_reset'
    | 'references'
    | 'done'
    | 'error';
  /** 回复标识（text_delta / tool_call_start / done） */
  replyId?: string;
  /** 文本块标识（text_delta） */
  blockId?: string;
  /** 文本增量（text_delta） */
  delta?: string;
  /** 工具调用标识（tool_call_* / tool_result） */
  toolCallId?: string;
  /** 工具名（tool_call_start / tool_result） */
  toolName?: string;
  /** 工具执行状态（tool_result，形如 SUCCESS / ERROR） */
  state?: string;
  /** 工具结果摘要（tool_result） */
  output?: string;
  /** 助手消息主键（done / tool_confirm） */
  messageId?: string | number;
  /** 工具调用次数（done） */
  toolCallCount?: number;
  /** 总 Token 数（done，可选） */
  totalTokens?: number;
  /** 耗时（毫秒，tool_result / done） */
  durationMs?: number;
  /** 错误信息（error） */
  message?: string;
  /** 待确认的工具调用（tool_confirm） */
  toolCalls?: ChatPendingToolCall[];
  /** 确认有效期（秒，tool_confirm） */
  expiresInSec?: number;
  /** 决策动作（route_decision：self 自答 / route 路由 / pinned 用户指定专家，REQ-AI-30/31） */
  action?: 'self' | 'route' | 'pinned';
  /** 专家标识（route_decision / expert_*） */
  expertKey?: string;
  /** 专家智能体主键（route_decision / expert_*，用于把轨迹里的原始结论挂回对应卡片） */
  expertAgentId?: string | number;
  /** 专家名称（route_decision / expert_*） */
  expertName?: string;
  /** 第几次尝试（expert_start） */
  attemptNo?: number;
  /** 调用类型（expert_start：first / retry / switch） */
  callKind?: string;
  /** 调用状态（expert_result：success / invalid / failed / timeout） */
  status?: string;
  /** 结构不合格说明（expert_invalid） */
  error?: string;
  /** 降级原因（route_fallback） */
  reason?: string;
  /** 参考来源（references / done 事件：文档名 + 章节 / 页码 + 相似度） */
  items?: ChatSourceItem[];
  /** 参考来源（done 事件，与 references 事件同结构） */
  references?: ChatSourceItem[];
}

/** 待确认的工具调用 */
export interface ChatPendingToolCall {
  /** AgentScope 工具调用 ID */
  toolCallId: string;
  /** 工具名称 */
  toolName: string;
  /** 工具入参 */
  input?: Record<string, any>;
}

/** 未决的工具确认（刷新页面后恢复确认卡片） */
export interface ChatPendingVO {
  /** 暂停时落库的助手消息主键 */
  messageId: string | number;
  /** AgentScope replyId */
  replyId?: string;
  /** 剩余有效期（秒） */
  expiresInSec?: number;
  /** 是否已过期（Redis 暂存已失效，无法再确认） */
  expired?: boolean;
  /** 待确认的工具调用 */
  toolCalls: ChatPendingToolCall[];
}

/** 工具确认请求体 */
export interface ChatConfirmBody {
  /** 暂停时落库的助手消息主键 */
  messageId: string | number;
  /** 是否确认执行（false 表示拒绝） */
  confirmed: boolean;
  /** 是否记住该工具（确认后改写该智能体 × 该工具的授权决策为「允许」） */
  remember?: boolean;
  /** 自定义拒绝原因（可选；仅 confirmed=false 时生效，回传模型并写入决策日志） */
  rejectReason?: string;
}

/** 专家调用明细（REQ-AI-30，来源标注与追溯的载体） */
export interface AiRouteExpertCallVO {
  /** 主键 */
  id: string | number;
  /** 被调用的专家智能体主键 */
  expertAgentId: string | number;
  /** 专家名称（后端由 ai_agent 回填，历史专家卡片渲染用） */
  expertAgentName?: string;
  /** 专家编码（后端由 ai_agent 回填，历史专家卡片渲染用） */
  expertAgentKey?: string;
  /** 第几次尝试（从 1 起） */
  attemptNo?: number;
  /** 调用类型（first / resume；历史数据可能含 retry / switch） */
  callKind?: string;
  /** 本次下发的输出契约（**已废弃**：专家直答修订后停写） */
  contractJson?: string;
  /** 专家输出正文（原文留证） */
  rawOutput?: string;
  /** 通过契约校验后的结构化结果（**已废弃**：专家直答修订后停写） */
  parsedOutput?: string;
  /** 失败原因（专家直答修订后为「专家不可用原因」） */
  validationError?: string;
  /** 调用状态（success 已回答 / failed / timeout / pending_confirm / cancelled 已取消；历史数据可能含 invalid / partial） */
  status?: string;
  /** 输入 Token */
  promptTokens?: number;
  /** 输出 Token */
  completionTokens?: number;
  /** 耗时（毫秒） */
  durationMs?: number;
}

/** 编排运行明细（REQ-AI-30，一次编排 + 其下全部专家调用） */
export interface AiRouteRunDetailVO {
  /** 编排主键 */
  id: string | number;
  /** 会话主键 */
  sessionId: string | number;
  /** 本轮的助手消息主键 */
  messageId?: string | number;
  /** 入口智能体主键 */
  entryAgentId?: string | number;
  /** 路由决策（self 自答 / route 路由） */
  action?: string;
  /** 决策原文（含 reason / confidence） */
  decisionJson?: string;
  /** 下发给专家的任务片段 */
  taskContext?: string;
  /** 专家调用次数（含重试与换专家） */
  expertCallCount?: number;
  /** 编排状态（running / success / degraded / aborted） */
  status?: string;
  /** 降级或中止原因 */
  degradeReason?: string;
  /** 编排总耗时（毫秒） */
  durationMs?: number;
  /** 专家调用明细 */
  expertCalls?: AiRouteExpertCallVO[];
}
