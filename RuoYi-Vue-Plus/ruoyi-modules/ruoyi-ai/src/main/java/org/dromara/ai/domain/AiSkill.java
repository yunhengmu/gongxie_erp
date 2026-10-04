package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 技能对象 ai_skill
 *
 * <p>{@code skill_content} 即 SKILL.md 全文（YAML frontmatter 承载 name / description，
 * 正文为 Markdown）；{@code name} 同时是 AgentScope 的 skillId（技能唯一标识）。</p>
 *
 * <p>本表列结构对 AgentScope 的 PostgreSQL 技能仓库实现保持兼容（装配时按 public schema +
 * {@code ai_skill} / {@code ai_skill_resource} + 只读构建），因此表由本项目 SQL 脚本创建、
 * 写入统一走本项目服务，从而保留审计列与生效范围治理列。</p>
 *
 * <p>不继承 {@code BaseEntity}：时间戳复用框架要求的 {@code created_at} / {@code updated_at}，
 * 避免同一含义出现两套时间列；删除为物理删除（框架按 name 直查，逻辑删除行会被继续装配）。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_skill")
public class AiSkill implements Serializable {

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    /**
     * 生效范围：公开
     */
    public static final String SCOPE_ALL = "0";

    /**
     * 生效范围：本部门
     */
    public static final String SCOPE_DEPT = "1";

    /**
     * 生效范围：仅本人
     */
    public static final String SCOPE_SELF = "2";

    /**
     * 本项目维护的技能来源标识
     */
    public static final String SOURCE_BIZBUDDY = "bizbuddy";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（雪花）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 技能标识（唯一，即 AgentScope 的 skillId）
     */
    private String name;

    /**
     * 技能用途说明（供模型判断是否加载）
     */
    private String description;

    /**
     * SKILL.md 全文
     */
    private String skillContent;

    /**
     * 技能来源
     */
    private String source;

    /**
     * 技能元数据 JSON（框架读取用，可为空）
     */
    private String metadataJson;

    /**
     * 创建时间（框架读取列）
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间（框架读取列）
     */
    private LocalDateTime updatedAt;

    /**
     * 归属部门（visible_scope = 1 时生效）
     */
    private Long ownerDeptId;

    /**
     * 生效范围（0公开 1本部门 2仅本人）
     */
    private String visibleScope;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建部门
     */
    private Long createDept;

    /**
     * 创建人
     */
    private Long createBy;

    /**
     * 更新人
     */
    private Long updateBy;

}
