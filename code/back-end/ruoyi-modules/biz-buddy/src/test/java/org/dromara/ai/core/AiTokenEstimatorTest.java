package org.dromara.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AiTokenEstimator} 单元测试：纯中文 / 纯英文 / 混合 / 空值，以及 Token 预算折算字符上限。
 */
@DisplayName("轻量 Token 估算器")
class AiTokenEstimatorTest {

    @Test
    @DisplayName("空值返回 0")
    void shouldReturnZeroForBlank() {
        assertEquals(0, AiTokenEstimator.estimateTokens(null));
        assertEquals(0, AiTokenEstimator.estimateTokens(""));
    }

    @Test
    @DisplayName("纯中文：1 字符 ≈ 1 token")
    void shouldEstimatePureChinese() {
        assertEquals(8, AiTokenEstimator.estimateTokens("员工考勤管理规定"));
        // 全角标点也按 CJK 计（（ 测试 ）= 4 个字符）
        assertEquals(4, AiTokenEstimator.estimateTokens("（测试）"));
    }

    @Test
    @DisplayName("纯英文：4 字符 ≈ 1 token，向上取整")
    void shouldEstimatePureEnglish() {
        assertEquals(1, AiTokenEstimator.estimateTokens("abcd"));
        assertEquals(2, AiTokenEstimator.estimateTokens("abcde"));
        assertEquals(3, AiTokenEstimator.estimateTokens("hello world"));
    }

    @Test
    @DisplayName("混合：CJK 逐字 + 非 CJK 四字折一")
    void shouldEstimateMixed() {
        // 4 个汉字 + 7 个非 CJK（含空格）→ 4 + ceil(7/4)=2 → 6
        assertEquals(6, AiTokenEstimator.estimateTokens("考勤 leave 管理"));
    }

    @Test
    @DisplayName("Token 预算折算字符上限：保守 1:1，非正数返回 0")
    void shouldConvertTokenBudgetToCharCap() {
        assertEquals(6000, AiTokenEstimator.tokenBudgetToCharCap(6000));
        assertEquals(3000, AiTokenEstimator.tokenBudgetToCharCap(3000));
        assertEquals(0, AiTokenEstimator.tokenBudgetToCharCap(0));
        assertEquals(0, AiTokenEstimator.tokenBudgetToCharCap(-1));
    }
}