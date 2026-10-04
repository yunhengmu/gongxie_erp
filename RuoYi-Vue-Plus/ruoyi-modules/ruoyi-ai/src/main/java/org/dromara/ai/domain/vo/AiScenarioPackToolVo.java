package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 场景包内业务工具视图对象（含授权决策）
 *
 * @author BizBuddy
 */
@Data
public class AiScenarioPackToolVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具编码
     */
    private String toolCode;

    /**
     * 工具名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String toolName;

    /**
     * 工具类型（附加字段，由 Service 填充；0只读 1写入）
     */
    @TableField(exist = false)
    private String toolType;

    /**
     * 授权决策（0允许 1需人工确认 2禁止）
     */
    private String decision;

}