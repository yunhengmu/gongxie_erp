package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.service.IAiQuotaService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * AI 配额校验 Service 业务层处理
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiQuotaServiceImpl implements IAiQuotaService {

    /**
     * 额度不限的取值
     */
    private static final long UNLIMITED = 0L;

    private final IAiUsageStatService usageStatService;

    @Override
    public void check(Long userId, AiAgent definition) {
        if (userId == null || definition == null) {
            return;
        }
        long dailyTokenQuota = definition.getDailyTokenQuota() == null ? UNLIMITED : definition.getDailyTokenQuota();
        long monthlyTokenQuota = definition.getMonthlyTokenQuota() == null ? UNLIMITED : definition.getMonthlyTokenQuota();
        long dailyRequestQuota = definition.getDailyRequestQuota() == null ? UNLIMITED : definition.getDailyRequestQuota();
        if (dailyTokenQuota <= UNLIMITED && monthlyTokenQuota <= UNLIMITED && dailyRequestQuota <= UNLIMITED) {
            return;
        }
        if (dailyTokenQuota > UNLIMITED) {
            long used = usageStatService.todayTokens(userId);
            if (used >= dailyTokenQuota) {
                throw new ServiceException("今日 Token 配额已用尽（" + used + "/" + dailyTokenQuota + "），请联系管理员");
            }
        }
        if (dailyRequestQuota > UNLIMITED) {
            long used = usageStatService.todayRequests(userId);
            if (used >= dailyRequestQuota) {
                throw new ServiceException("今日提问次数已达上限（" + used + "/" + dailyRequestQuota + "），请联系管理员");
            }
        }
        if (monthlyTokenQuota > UNLIMITED) {
            long used = usageStatService.monthTokens(userId);
            if (used >= monthlyTokenQuota) {
                throw new ServiceException("本月 Token 配额已用尽（" + used + "/" + monthlyTokenQuota + "），请联系管理员");
            }
        }
    }

}