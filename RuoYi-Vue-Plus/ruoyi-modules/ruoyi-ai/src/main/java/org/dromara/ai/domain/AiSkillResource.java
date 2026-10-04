package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 技能参考资料对象 ai_skill_resource
 *
 * <p>主键为「所属技能主键 + 相对路径」复合键，技能物理删除时随外键级联清理。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_skill_resource")
public class AiSkillResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属技能主键（ai_skill.id）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 相对路径（如 references/faq.md）
     */
    private String resourcePath;

    /**
     * 文件内容
     */
    private String resourceContent;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

}
