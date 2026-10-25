package org.dromara.ai.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型费用折算工具：按「输入 / 输出单价（元 / 千 Token）」把 Token 用量折算为费用（元）。
 *
 * <p>与用量统计（{@code AiUsageStatServiceImpl#estimateCost}）同款口径：千 Token 基数、精度 6 位、四舍五入；
 * 单价为空按 0 计（未配置单价即不计费，由调用方在展示层标注）。</p>
 *
 * @author BizBuddy
 */
public final class AiModelCost {

    /**
     * 费用精度
     */
    private static final int COST_SCALE = 6;

    /**
     * 千 Token 基数
     */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private AiModelCost() {
    }

    /**
     * 折算一段 Token 用量的费用。
     *
     * @param inputPrice      输入单价（元 / 千 Token，可空，空按 0）
     * @param outputPrice     输出单价（元 / 千 Token，可空，空按 0）
     * @param promptTokens    输入 Token
     * @param completionTokens 输出 Token
     * @return 费用（元，精度 6 位）
     */
    public static BigDecimal cost(BigDecimal inputPrice, BigDecimal outputPrice, long promptTokens, long completionTokens) {
        BigDecimal input = inputPrice == null ? BigDecimal.ZERO : inputPrice;
        BigDecimal output = outputPrice == null ? BigDecimal.ZERO : outputPrice;
        return BigDecimal.valueOf(promptTokens).divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP).multiply(input)
            .add(BigDecimal.valueOf(completionTokens).divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP).multiply(output))
            .setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

}