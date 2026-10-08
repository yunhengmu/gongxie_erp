package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务工具与标签关联对象 ai_tool_tag_rel
 *
 * <p>追加型物理表（保存工具时**先删后插**），不设 {@code del_flag}。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_tool_tag_rel")
public class AiToolTagRel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 业务工具主键（ai_tool.id）
     */
    private Long toolId;

    /**
     * 标签主键（ai_tool_tag.id）
     */
    private Long tagId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}