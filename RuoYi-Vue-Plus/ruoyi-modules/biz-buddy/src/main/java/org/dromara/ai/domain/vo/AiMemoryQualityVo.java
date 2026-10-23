package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 长期记忆运行健康视图对象（REQ-AI-09）。
 *
 * <p>从审计日志聚合近 N 天的抽取与合并结果，用于回答「记忆是否还在工作」。
 * 只包含运行指标，不含语义召回率——语义质量需要标注样本，由离线门禁评测。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryQualityVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 统计窗口（天）
     */
    private int days;

    /**
     * 抽取执行次数（成功的平台抽取）
     */
    private int extractRuns;

    /**
     * 其中无产出的次数（模型输出 NO_REPLY，kept=0）
     */
    private int extractEmpty;

    /**
     * 保留的事实条数
     */
    private long extractKept;

    /**
     * 丢弃的 needs_context 条数
     */
    private int needsContext;

    /**
     * 丢弃的低置信条数
     */
    private int lowConfidence;

    /**
     * 抽取失败次数
     */
    private int extractFailures;

    /**
     * 合并执行次数
     */
    private int consolidateRuns;

    /**
     * 合并失败次数
     */
    private int consolidateFailures;

    /**
     * 最近一次合并耗时（毫秒）
     */
    private long lastConsolidateMs;

    /**
     * 最近一次合并消耗的 Token
     */
    private long lastConsolidateTokens;

    /**
     * 最近一次成功抽取时间（ISO 时间，无记录时为空串）
     */
    private String lastExtractAt;

    /**
     * 最近一次成功合并时间（ISO 时间，无记录时为空串）
     */
    private String lastConsolidateAt;

}
