package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台健康自检报告（REQ-AI-35）。
 *
 * <p>{@link #overall} 口径：有 {@code FAIL} → FAIL；否则有 {@code WARN} → WARN；否则 PASS。
 * {@code UNKNOWN}（待探测）**不计入失败**，但也不给绿灯——前端按「待探测」单独标注。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiHealthReportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 整体结论（PASS / WARN / FAIL）
     */
    private String overall;

    /**
     * 体检项总数
     */
    private int total;

    /**
     * 通过项数
     */
    private int passCount;

    /**
     * 告警项数
     */
    private int warnCount;

    /**
     * 失败项数
     */
    private int failCount;

    /**
     * 待探测项数
     */
    private int unknownCount;

    /**
     * 自检时刻
     */
    private LocalDateTime checkedAt;

    /**
     * 体检项明细
     */
    private List<AiHealthCheckVo> items;

}
