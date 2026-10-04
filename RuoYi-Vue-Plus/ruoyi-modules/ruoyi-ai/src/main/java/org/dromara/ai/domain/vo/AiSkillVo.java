package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSkill;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 技能视图对象 ai_skill
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSkill.class)
public class AiSkillVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 技能标识（即 AgentScope 的 skillId）
     */
    private String name;

    /**
     * 技能用途说明
     */
    private String description;

    /**
     * SKILL.md 全文（frontmatter + 正文，落库值）
     */
    private String skillContent;

    /**
     * SKILL.md 正文（附加字段，由 Service 从全文剥离 frontmatter 得到，供编辑）
     */
    @TableField(exist = false)
    private String skillBody;

    /**
     * 技能来源
     */
    private String source;

    /**
     * 归属部门
     */
    private Long ownerDeptId;

    /**
     * 归属部门名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String ownerDeptName;

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
     * 创建人
     */
    private Long createBy;

    /**
     * 创建人昵称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String createByName;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 参考资料数量（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Integer resourceCount;

    /**
     * 使用计数（附加字段：技能加载工具被调用的累计次数，由 Service 聚合工具调用明细填充）
     */
    @TableField(exist = false)
    private Integer useCount;

    /**
     * 最近使用时间（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private LocalDateTime lastUsedAt;

    /**
     * 陈旧归档状态（附加字段：active 活跃 / stale 陈旧 / archived 待归档 / disabled 停用）
     */
    @TableField(exist = false)
    private String lifecycleState;

    /**
     * 参考资料明细（附加字段，仅详情与编辑时返回）
     */
    @TableField(exist = false)
    private List<AiSkillResourceVo> resources;

}
