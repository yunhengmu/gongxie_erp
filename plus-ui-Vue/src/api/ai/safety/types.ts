/**
 * AI 安全合规类型定义（敏感词库 / 安全事件）
 */

/** 敏感词库明细 */
export interface SafetyWordVO {
  /** 主键 */
  id?: string | number;
  /** 敏感词（或正则表达式） */
  word?: string;
  /** 是否正则（0 否 / 1 是） */
  isRegex?: string;
  /** 分类 */
  category?: string;
  /** 状态（1 启用 / 0 禁用） */
  enabled?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
}

/** 敏感词库查询条件 */
export interface SafetyWordQuery extends PageQuery {
  /** 敏感词（模糊匹配） */
  word?: string;
  /** 分类 */
  category?: string;
  /** 状态（1 启用 / 0 禁用） */
  enabled?: string;
}

/** 敏感词新增 / 修改表单 */
export interface SafetyWordForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 敏感词（或正则表达式） */
  word?: string;
  /** 是否正则（0 否 / 1 是） */
  isRegex?: string;
  /** 分类 */
  category?: string;
  /** 状态（1 启用 / 0 禁用） */
  enabled?: string;
  /** 备注 */
  remark?: string;
}

/** 安全事件 */
export interface SafetyEventVO {
  /** 主键 */
  id?: string | number;
  /** 场景（input 输入 / output 输出 / channel_out 渠道出站） */
  scene?: string;
  /** 命中动作（log 只记录 / mask 脱敏放行 / block 阻断） */
  action?: string;
  /** 检测引擎（local / external） */
  engine?: string;
  /** 会话主键 */
  sessionId?: string | number;
  /** 消息主键 */
  messageId?: string | number;
  /** 智能体主键 */
  agentId?: string | number;
  /** 用户主键 */
  userId?: string | number;
  /** 用户昵称 */
  userName?: string;
  /** 命中次数 */
  hitCount?: number;
  /** 命中明细（JSON） */
  hitsJson?: string;
  /** 命中文本片段 */
  textExcerpt?: string;
  /** 创建时间 */
  createTime?: string;
}

/** 安全事件查询条件 */
export interface SafetyEventQuery extends PageQuery {
  /** 场景（input / output / channel_out） */
  scene?: string;
  /** 命中动作（log / mask / block） */
  action?: string;
  /** 用户主键 */
  userId?: string | number;
  /** 时间起（YYYY-MM-DD HH:mm:ss） */
  beginTime?: string;
  /** 时间止（YYYY-MM-DD HH:mm:ss） */
  endTime?: string;
}