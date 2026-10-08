package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 用量汇总行（按日期 / 智能体 / 模型分组）
 *
 * @author BizBuddy
 */
@Data
public class AiUsageSummaryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分组键（日期 / 智能体主键 / 模型主键）
     */
    private String dimension;

    /**
     * 分组显示名
     */
    private String label;

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
    private Long requestCount;

    /**
     * 工具调用次数
     */
    private Long toolCallCount;

    /**
     * 费用估算（元）
     */
    private BigDecimal cost;

    /**
     * 累加一条用量。
     *
     * @param prompt     输入 Token
     * @param completion 输出 Token
     * @param requests   请求数
     * @param toolCalls  工具调用次数
     * @param rowCost    费用
     */
    public void accumulate(Long prompt, Long completion, Integer requests, Integer toolCalls, BigDecimal rowCost) {
        this.promptTokens = (promptTokens == null ? 0L : promptTokens) + (prompt == null ? 0L : prompt);
        this.completionTokens = (completionTokens == null ? 0L : completionTokens) + (completion == null ? 0L : completion);
        this.totalTokens = (totalTokens == null ? 0L : totalTokens) + (prompt == null ? 0L : prompt) + (completion == null ? 0L : completion);
        this.requestCount = (requestCount == null ? 0L : requestCount) + (requests == null ? 0L : requests);
        this.toolCallCount = (toolCallCount == null ? 0L : toolCallCount) + (toolCalls == null ? 0L : toolCalls);
        this.cost = (cost == null ? BigDecimal.ZERO : cost).add(rowCost == null ? BigDecimal.ZERO : rowCost);
    }

}