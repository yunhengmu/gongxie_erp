package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 用量明细视图对象
 *
 * @author BizBuddy
 */
@Data
public class AiUsageStatVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
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
     * 智能体名称
     */
    private String agentName;

    /**
     * 模型主键
     */
    private Long modelId;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 用户主键
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用量类型（0对话 1知识库入库）
     */
    private String usageType;

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
     * 模型是否已配置单价（未配置时费用按 0 计）
     */
    private Boolean priceConfigured;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}