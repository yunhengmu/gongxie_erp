package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 工具调用统计视图对象（按工具聚合）
 *
 * @author BizBuddy
 */
@Data
public class AiToolCallStatVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 调用次数
     */
    private long totalCount;

    /**
     * 成功次数
     */
    private long successCount;

    /**
     * 失败次数
     */
    private long failCount;

    /**
     * 平均耗时（毫秒）
     */
    private long avgDurationMs;

    /**
     * 失败率（百分比，保留 2 位）
     */
    private BigDecimal failRate;

    /**
     * 依据累计值计算失败率与平均耗时。
     *
     * @param totalDurationMs 累计耗时（毫秒）
     */
    public void finish(long totalDurationMs) {
        failCount = totalCount - successCount;
        failRate = totalCount == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(failCount * 100L).divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
        avgDurationMs = totalCount == 0 ? 0 : totalDurationMs / totalCount;
    }

}
