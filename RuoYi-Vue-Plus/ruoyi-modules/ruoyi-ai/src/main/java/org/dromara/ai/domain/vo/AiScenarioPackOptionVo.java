package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiScenarioPack;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 场景包选项视图对象（供创建任务时选择场景包）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiScenarioPack.class)
public class AiScenarioPackOptionVo implements Serializable {

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
     * 用途说明（供用户判断该选哪个包）
     */
    private String description;

    /**
     * 该包已配置的业务工具数量（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Integer toolCount;

    /**
     * 该包已配置的外部 MCP 工具数量（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Integer mcpToolCount;

    /**
     * 该包已配置的技能数量（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Integer skillCount;

}