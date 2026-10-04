package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiTool;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务工具注册视图对象 ai_tool
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiTool.class)
public class AiToolVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 工具编码
     */
    private String toolCode;

    /**
     * 工具显示名
     */
    private String toolName;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 工具类型（0只读 1写入）
     */
    private String toolType;

    /**
     * 参数定义（JSON Schema）
     */
    private String paramsSchema;

    /**
     * 代码侧实现标识
     */
    private String implName;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 已打标签（附加字段，由 Service 填充；无标签时为空列表）
     */
    @TableField(exist = false)
    private List<AiToolTagVo> tags;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
