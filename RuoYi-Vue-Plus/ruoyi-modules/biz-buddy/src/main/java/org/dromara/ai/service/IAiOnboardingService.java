package org.dromara.ai.service;

import org.dromara.ai.domain.vo.AiOnboardingReportVo;

/**
 * 首登检查清单 Service。
 *
 * @author BizBuddy
 */
public interface IAiOnboardingService {

    /**
     * 计算当前用户的首登检查清单。
     *
     * <p>只读取现有数据给出「是否完成」，不修改任何配置、不发起外网调用。</p>
     *
     * @return 清单报告
     */
    AiOnboardingReportVo checklist();

}
