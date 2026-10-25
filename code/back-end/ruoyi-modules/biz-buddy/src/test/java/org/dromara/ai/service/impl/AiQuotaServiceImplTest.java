package org.dromara.ai.service.impl;

import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiQuotaServiceImpl} 单元测试：配额短路、三档配额命中与放行。
 */
@DisplayName("AI 配额校验服务")
class AiQuotaServiceImplTest {

    private final IAiUsageStatService usageStatService = mock(IAiUsageStatService.class);
    private final AiQuotaServiceImpl service = new AiQuotaServiceImpl(usageStatService);

    @Test
    @DisplayName("用户或智能体定义为空时直接放行")
    void shouldSkipWhenUserOrDefinitionNull() {
        service.check(null, new AiExpertAgent());
        service.check(1L, null);
        verifyNoInteractions(usageStatService);
    }

    @Test
    @DisplayName("三档配额均为 0 或空时不校验用量")
    void shouldSkipWhenNoQuotaConfigured() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setDailyTokenQuota(0L);
        agent.setMonthlyTokenQuota(null);
        agent.setDailyRequestQuota(0);

        service.check(1L, agent);

        verifyNoInteractions(usageStatService);
    }

    @Test
    @DisplayName("日 Token 配额用尽时抛出异常")
    void shouldThrowWhenDailyTokenExhausted() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setDailyTokenQuota(100L);
        when(usageStatService.todayTokens(1L)).thenReturn(100L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.check(1L, agent));
        assertEquals("今日 Token 配额已用尽（100/100），请联系管理员", ex.getMessage());
    }

    @Test
    @DisplayName("日提问次数达上限时抛出异常")
    void shouldThrowWhenDailyRequestExhausted() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setDailyRequestQuota(5);
        when(usageStatService.todayRequests(1L)).thenReturn(6L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.check(1L, agent));
        assertEquals("今日提问次数已达上限（6/5），请联系管理员", ex.getMessage());
    }

    @Test
    @DisplayName("月 Token 配额用尽时抛出异常")
    void shouldThrowWhenMonthlyTokenExhausted() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setMonthlyTokenQuota(1000L);
        when(usageStatService.monthTokens(1L)).thenReturn(1000L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.check(1L, agent));
        assertEquals("本月 Token 配额已用尽（1000/1000），请联系管理员", ex.getMessage());
    }

    @Test
    @DisplayName("未达配额上限时放行并逐档校验")
    void shouldPassWhenWithinQuota() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setDailyTokenQuota(100L);
        agent.setDailyRequestQuota(10);
        agent.setMonthlyTokenQuota(1000L);
        when(usageStatService.todayTokens(1L)).thenReturn(99L);
        when(usageStatService.todayRequests(1L)).thenReturn(9L);
        when(usageStatService.monthTokens(1L)).thenReturn(999L);

        assertDoesNotThrow(() -> service.check(1L, agent));
    }

}