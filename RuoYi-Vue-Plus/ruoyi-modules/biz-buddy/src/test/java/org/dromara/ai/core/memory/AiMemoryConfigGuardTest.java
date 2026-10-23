package org.dromara.ai.core.memory;

import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AiMemoryConfigGuard} 单元测试：覆盖保留期与预算的跨字段护栏。
 */
@DisplayName("长期记忆配置护栏")
class AiMemoryConfigGuardTest {

    @Test
    @DisplayName("合法配置通过校验")
    void shouldAcceptValidPolicy() {
        assertDoesNotThrow(() -> AiMemoryConfigGuard.validate(policy(90, 365, 8000, 4000)));
    }

    @Test
    @DisplayName("未知管道模式时拒绝")
    void shouldRejectUnknownPipelineMode() {
        AiMemoryConfigBo policy = policy(90, 365, 8000, 4000);
        policy.setPipelineMode("auto");
        ServiceException e = assertThrows(ServiceException.class,
            () -> AiMemoryConfigGuard.validate(policy));
        assertEquals("记忆管道模式只能是 framework / shadow / platform", e.getMessage());
    }

    @Test
    @DisplayName("归档保留期短于流水保留期时拒绝")
    void shouldRejectArchiveShorterThanDaily() {
        ServiceException e = assertThrows(ServiceException.class,
            () -> AiMemoryConfigGuard.validate(policy(90, 30, 8000, 4000)));
        assertEquals("归档保留天数不能小于流水保留天数，否则归档后会立即被清理", e.getMessage());
    }

    @Test
    @DisplayName("合并预算超过注入预算 60% 时拒绝")
    void shouldRejectOversizedConsolidationBudget() {
        // 8000 × 60% = 4800 Token；合并预算 5000 超过该上限
        ServiceException e = assertThrows(ServiceException.class,
            () -> AiMemoryConfigGuard.validate(policy(90, 365, 8000, 5000)));
        assertEquals("合并 Token 上限过大：不能超过记忆注入预算的 60%", e.getMessage());
    }

    private AiMemoryConfigBo policy(int dailyRetention, int archiveRetention, int maxContextTokens,
                                    int consolidationMaxTokens) {
        AiMemoryConfigBo policy = new AiMemoryConfigBo();
        policy.setDailyFileRetentionDays(dailyRetention);
        policy.setArchiveRetentionDays(archiveRetention);
        policy.setMaxContextTokens(maxContextTokens);
        policy.setConsolidationMaxTokens(consolidationMaxTokens);
        return policy;
    }
}
