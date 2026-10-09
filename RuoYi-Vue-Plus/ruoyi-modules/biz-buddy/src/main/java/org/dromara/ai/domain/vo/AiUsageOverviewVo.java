package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 用量总览（合计 + 三个维度分组）
 *
 * @author BizBuddy
 */
@Data
public class AiUsageOverviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 合计
     */
    private AiUsageSummaryVo total;

    /**
     * 按日期分组
     */
    private List<AiUsageSummaryVo> byDate;

    /**
     * 按智能体分组
     */
    private List<AiUsageSummaryVo> byAgent;

    /**
     * 按模型分组
     */
    private List<AiUsageSummaryVo> byModel;

}