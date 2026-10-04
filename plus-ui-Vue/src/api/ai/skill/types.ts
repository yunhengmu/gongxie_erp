/**
 * AI 技能仓库类型定义（技能仓库页与智能体定义页）
 */

export interface SkillResourceVO {
  /** 所属技能主键 */
  id: string | number;
  /** 相对路径（如 references/faq.md） */
  resourcePath: string;
  /** 文件内容 */
  resourceContent?: string;
}

export interface SkillResourceForm {
  /** 相对路径（如 references/faq.md） */
  resourcePath: string;
  /** 文件内容 */
  resourceContent?: string;
}

export interface SkillVO {
  /** 主键 */
  id: string | number;
  /** 技能标识（即 AgentScope 的 skillId） */
  name?: string;
  /** 技能用途说明 */
  description?: string;
  /** SKILL.md 全文（frontmatter + 正文） */
  skillContent?: string;
  /** SKILL.md 正文（不含 frontmatter） */
  skillBody?: string;
  /** 技能来源 */
  source?: string;
  /** 归属部门 */
  ownerDeptId?: string | number;
  /** 归属部门名称 */
  ownerDeptName?: string;
  /** 生效范围（0公开 1本部门 2仅本人） */
  visibleScope?: string;
  /** 状态（0启用 1停用） */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 创建人 */
  createBy?: string | number;
  /** 创建人昵称 */
  createByName?: string;
  /** 创建时间 */
  createdAt?: string;
  /** 更新时间 */
  updatedAt?: string;
  /** 参考资料数量 */
  resourceCount?: number;
  /** 使用计数（技能加载工具被调用的累计次数） */
  useCount?: number;
  /** 最近使用时间 */
  lastUsedAt?: string;
  /** 陈旧归档状态（active / stale / archived / disabled） */
  lifecycleState?: string;
  /** 参考资料明细 */
  resources?: SkillResourceVO[];
}

export interface SkillQuery extends PageQuery {
  /** 标识 / 说明模糊匹配 */
  keyword?: string;
  /** 状态（0启用 1停用） */
  status?: string;
}

export interface SkillForm {
  /** 主键（新增时为空） */
  id?: string | number;
  /** 技能标识 */
  name?: string;
  /** 技能用途说明 */
  description?: string;
  /** SKILL.md 正文 */
  skillBody?: string;
  /** 归属部门 */
  ownerDeptId?: string | number;
  /** 生效范围 */
  visibleScope?: string;
  /** 状态 */
  status?: string;
  /** 备注 */
  remark?: string;
  /** 参考资料明细 */
  resources?: SkillResourceForm[];
}

export interface SkillOptionVO {
  /** 主键 */
  id: string | number;
  /** 技能标识 */
  name?: string;
  /** 技能用途说明 */
  description?: string;
}
