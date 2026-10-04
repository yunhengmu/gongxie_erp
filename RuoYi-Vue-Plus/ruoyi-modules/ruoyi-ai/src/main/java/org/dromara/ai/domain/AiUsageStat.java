package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 用量统计对象 ai_usage_stat
 *
 * <p>按「日期 + 智能体 + 模型 + 用户」聚合，对话结束时累加写入（PostgreSQL upsert）。
 * 表中无创建人 / 部门字段，故不继承 BaseEntity。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_usage_stat")
public class AiUsageStat implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 统计日期
     */
    private LocalDate statDate;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 模型主键（ai_model_config.id）
     */
    private Long modelId;

    /**
     * 用户主键
     */
    private Long userId;

    /**
     * 输入 Token 数
     */
    private Long promptTokens;

    /**
     * 输出 Token 数
     */
    private Long completionTokens;

    /**
     * 总 Token 数
     */
    private Long totalTokens;

    /**
     * 请求（对话轮次）数
     */
    private Integer requestCount;

    /**
     * 工具调用次数
     */
    private Integer toolCallCount;

    /**
     * 费用估算（元）
     */
    private BigDecimal cost;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}