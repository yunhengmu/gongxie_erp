package org.dromara.ai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AiModelCost} 单元测试：Token 费用折算口径。
 */
@DisplayName("模型费用折算")
class AiModelCostTest {

    @Test
    @DisplayName("按输入/输出单价与千 Token 基数折算")
    void shouldComputeCost() {
        // 1000/1000×0.001 + 2000/1000×0.02 = 0.001 + 0.04 = 0.041
        BigDecimal cost = AiModelCost.cost(new BigDecimal("0.001"), new BigDecimal("0.02"), 1000L, 2000L);
        assertEquals(0, new BigDecimal("0.041").compareTo(cost));
    }

    @Test
    @DisplayName("单价为空按 0 计")
    void shouldTreatNullPriceAsZero() {
        BigDecimal cost = AiModelCost.cost(null, null, 1234L, 5678L);
        assertEquals(0, BigDecimal.ZERO.compareTo(cost));
    }

    @Test
    @DisplayName("金额精度按 6 位四舍五入")
    void shouldRoundToSixScale() {
        BigDecimal cost = AiModelCost.cost(new BigDecimal("0.0000005"), BigDecimal.ZERO, 1000L, 0L);
        assertEquals(6, cost.scale());
        assertEquals(0, new BigDecimal("0.000001").compareTo(cost));
    }

    @Test
    @DisplayName("Token 为 0 时费用为 0")
    void shouldBeZeroWhenNoTokens() {
        BigDecimal cost = AiModelCost.cost(new BigDecimal("0.01"), new BigDecimal("0.02"), 0L, 0L);
        assertEquals(0, BigDecimal.ZERO.compareTo(cost));
    }

}