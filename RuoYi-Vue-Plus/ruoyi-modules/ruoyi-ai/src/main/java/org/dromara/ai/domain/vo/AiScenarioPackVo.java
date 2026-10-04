package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiScenarioPack;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 场景包视图对象 ai_scenario_pack
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiScenarioPack.class)
public class AiScenarioPackVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 场景包编码
     */
    private String packCode;

    /**
     * 场景包名称
     */
    private String packName;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 生效范围（0 公开 / 1 本部门 / 2 仅本人）
     */
    private String visibleScope;

    /**
     * 归属部门 ID
     */
    private Long ownerDeptId;

    /**
     * 归属部门名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String ownerDeptName;

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
     * 包内业务工具及授权决策（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<AiScenarioPackToolVo> toolBindings;

    /**
     * 包内外部 MCP 工具及授权决策（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<AiScenarioPackMcpToolVo> mcpToolBindings;

    /**
     * 包内技能主键集合（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<Long> skillIds;

    /**
     * 包内技能名称集合（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<String> skillNames;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}