package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiToolTag;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务工具标签视图对象 ai_tool_tag
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiToolTag.class)
public class AiToolTagVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 标签名称
     */
    private String tagName;

    /**
     * 备注（用途说明）
     */
    private String remark;

    /**
     * 使用该标签的工具数（附加字段，由 Service 聚合；用于发现与清理无用标签）
     */
    @TableField(exist = false)
    private Long useCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}