/**
 * AI 模型配置类型定义
 */

export interface ModelConfigVO {
  /** 主键 */
  id: string | number;
  /** 配置名称 */
  configName: string;
  /** 提供商 */
  provider: string;
  /** 模型类型（chat 对话 / embedding 向量） */
  modelType: string;
  /** 模型标识 */
  modelCode: string;
  /** 服务地址 */
  baseUrl: string;
  /** 密钥掩码：已配置为 ******，未配置为空串 */
  apiKeyMask: string;
  /** 向量维度 */
  dimension?: number;
  /** 超时时间（秒） */
  timeoutSeconds?: number;
  /** 失败重试次数 */
  maxRetries?: number;
  /** 输入单价（元 / 千 Token），为空表示未配置 */
  inputPrice?: number;
  /** 输出单价（元 / 千 Token），为空表示未配置 */
  outputPrice?: number;
  /** 状态（0正常 1停用） */
  status: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface ModelConfigForm extends BaseEntity {
  /** 主键 */
  id?: string | number;
  /** 配置名称 */
  configName?: string;
  /** 提供商 */
  provider?: string;
  /** 模型类型（chat 对话 / embedding 向量） */
  modelType?: string;
  /** 模型标识 */
  modelCode?: string;
  /** 服务地址 */
  baseUrl?: string;
  /** 密钥明文：新增时填写，修改时留空表示不修改 */
  apiKey?: string;
  /** 向量维度 */
  dimension?: number;
  /** 超时时间（秒） */
  timeoutSeconds?: number;
  /** 失败重试次数 */
  maxRetries?: number;
  /** 输入单价（元 / 千 Token） */
  inputPrice?: number;
  /** 输出单价（元 / 千 Token） */
  outputPrice?: number;
  /** 状态（0正常 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
}

export interface ModelConfigQuery extends PageQuery {
  /** 配置名称 */
  configName?: string;
  /** 提供商 */
  provider?: string;
  /** 模型类型 */
  modelType?: string;
  /** 状态 */
  status?: string;
}

export interface ModelTestResult {
  /** 是否连通 */
  success: boolean;
  /** 结果说明 */
  message: string;
  /** 耗时（毫秒） */
  elapsedMs: number;
}