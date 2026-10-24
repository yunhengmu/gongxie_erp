/**
 * AI 业务工具注册类型定义
 */

/** 工具标签 */
export interface ToolTagVO {
  /** 主键 */
  id: string | number;
  /** 标签名称（唯一） */
  tagName: string;
  /** 备注（用途说明） */
  remark?: string;
  /** 使用该标签的工具数 */
  useCount?: number;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface ToolVO {
  /** 主键 */
  id: string | number;
  /** 工具编码（唯一，创建后不可修改） */
  toolCode: string;
  /** 工具显示名 */
  toolName: string;
  /** 一句话用途说明（供模型理解） */
  description?: string;
  /** 工具类型（0只读 1写入） */
  toolType?: string;
  /** 参数定义（JSON Schema 字符串） */
  paramsSchema?: string;
  /** 代码侧实现标识（需已在代码中登记） */
  implName?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 已打标签（无标签时为空数组） */
  tags?: ToolTagVO[];
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

export interface ToolForm extends BaseEntity {
  /** 主键（有值为修改，无值为新增） */
  id?: string | number;
  /** 工具编码（唯一，修改时后端禁止改编码） */
  toolCode?: string;
  /** 工具显示名 */
  toolName?: string;
  /** 一句话用途说明（供模型理解） */
  description?: string;
  /** 工具类型（0只读 1写入） */
  toolType?: string;
  /** 参数定义（JSON Schema 字符串） */
  paramsSchema?: string;
  /** 代码侧实现标识（需已在代码中登记） */
  implName?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 标签主键集合（覆盖式设置；空数组表示清除全部标签） */
  tagIds?: Array<string | number>;
  /** 备注 */
  remark?: string;
}

export interface ToolQuery extends PageQuery {
  /** 关键字（编码 / 名称模糊匹配） */
  keyword?: string;
  /** 工具类型（0只读 1写入） */
  toolType?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 标签筛选（逗号分隔的标签主键；语义为「任一命中」） */
  tagIds?: string;
  /** 只看未打任何标签的工具 */
  noTag?: boolean;
}

export interface ToolTagForm {
  /** 主键（有值为修改） */
  id?: string | number;
  /** 标签名称（唯一） */
  tagName?: string;
  /** 备注（用途说明） */
  remark?: string;
}

export interface ToolTagQuery extends PageQuery {
  /** 标签名称（模糊匹配） */
  keyword?: string;
}

/** 工具标签批量操作（批量打标签 / 批量移除标签） */
export interface ToolTagBatchForm {
  /** 目标工具主键集合 */
  toolIds: Array<string | number>;
  /** 标签主键集合 */
  tagIds: Array<string | number>;
}