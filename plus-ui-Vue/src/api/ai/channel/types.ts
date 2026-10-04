/**
 * AI 渠道接入类型定义（渠道接入页）
 */

export interface ChannelVO {
  /** 主键 */
  id: string | number;
  /** 渠道名称 */
  channelName?: string;
  /** 渠道编码（回调地址中的路径标识） */
  channelCode?: string;
  /** 平台类型（feishu / wecom / github / dingtalk） */
  platformType?: string;
  /** 默认智能体主键 */
  agentId?: string | number;
  /** 默认智能体名称 */
  agentName?: string;
  /** 默认智能体状态（1 表示已停用，渠道将无法执行） */
  agentStatus?: string;
  /** 应用标识（飞书 appId / 企微 corpId） */
  appId?: string;
  /** 平台内应用 ID（企微 agentId / GitHub 机器人账号登录名 / 钉钉机器人编码 RobotCode） */
  platformAgentId?: string;
  /** 平台 API 基地址 */
  apiBase?: string;
  /** 是否已配置应用密钥 */
  appSecretSet?: boolean;
  /** 是否已配置回调加解密密钥 */
  encryptKeySet?: boolean;
  /** 是否已配置回调校验口令 */
  verificationTokenSet?: boolean;
  /** 是否已配置 Webhook 签名密钥 */
  webhookSecretSet?: boolean;
  /** 是否已配置平台访问令牌 */
  apiTokenSet?: boolean;
  /** 长连接渠道（钉钉）的运行状态（stopped / running / failed） */
  streamStatus?: string;
  /** 长连接启动失败原因 */
  streamStatusDetail?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface ChannelQuery extends PageQuery {
  /** 渠道名称 / 编码（模糊匹配） */
  keyword?: string;
  /** 平台类型 */
  platformType?: string;
  /** 状态（0启用 1停用） */
  status?: string;
}

export interface ChannelForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 渠道名称 */
  channelName?: string;
  /** 渠道编码 */
  channelCode?: string;
  /** 平台类型 */
  platformType?: string;
  /** 默认智能体 */
  agentId?: string | number;
  /** 应用标识 */
  appId?: string;
  /** 应用密钥（明文入参，留空表示保持原值） */
  appSecret?: string;
  /** 平台内应用 ID（企微 agentId / GitHub 机器人账号登录名 / 钉钉机器人编码 RobotCode） */
  platformAgentId?: string;
  /** 回调加解密密钥（明文入参，留空表示保持原值） */
  encryptKey?: string;
  /** 回调校验口令（明文入参，留空表示保持原值） */
  verificationToken?: string;
  /** Webhook 签名密钥（明文入参，留空表示保持原值） */
  webhookSecret?: string;
  /** 平台访问令牌（明文入参，留空表示保持原值） */
  apiToken?: string;
  /** 平台 API 基地址 */
  apiBase?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 是否已配置应用密钥（回显用，不提交） */
  appSecretSet?: boolean;
  /** 是否已配置回调加解密密钥（回显用，不提交） */
  encryptKeySet?: boolean;
  /** 是否已配置回调校验口令（回显用，不提交） */
  verificationTokenSet?: boolean;
  /** 是否已配置 Webhook 签名密钥（回显用，不提交） */
  webhookSecretSet?: boolean;
  /** 是否已配置平台访问令牌（回显用，不提交） */
  apiTokenSet?: boolean;
}

export interface ChannelIdentityVO {
  /** 主键 */
  id: string | number;
  /** 所属渠道 */
  channelId?: string | number;
  /** 所属渠道名称 */
  channelName?: string;
  /** 外部用户标识 */
  externalUserId?: string;
  /** 映射到的平台用户 */
  platformUserId?: string | number;
  /** 平台用户昵称 */
  platformUserName?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface ChannelIdentityQuery extends PageQuery {
  /** 所属渠道 */
  channelId?: string | number;
  /** 外部用户标识（模糊匹配） */
  keyword?: string;
}

export interface ChannelIdentityForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 所属渠道 */
  channelId?: string | number;
  /** 外部用户标识 */
  externalUserId?: string;
  /** 映射到的平台用户 */
  platformUserId?: string | number;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
}

export interface ChannelThreadVO {
  /** 主键 */
  id: string | number;
  /** 所属渠道 */
  channelId?: string | number;
  /** 所属渠道名称 */
  channelName?: string;
  /** 线程标识 */
  threadKey?: string;
  /** 映射到的会话 */
  sessionId?: string | number;
  /** 会话标题 */
  sessionTitle?: string;
  /** 会话状态（0进行中 1已结束） */
  sessionStatus?: string;
  /** 创建时间 */
  createTime?: string;
  /** 最后活跃时间 */
  lastActiveTime?: string;
}