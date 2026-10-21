package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.vo.AiOnboardingReportVo;
import org.dromara.ai.service.IAiOnboardingService;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首登检查清单 Controller。
 *
 * <p>只读：复用健康自检的模型结论 + 文档 / 消息状态，不做任何配置写入或外网调用。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/onboarding")
public class AiOnboardingController extends BaseController {

    private final IAiOnboardingService onboardingService;

    /**
     * 首登检查清单（权限复用健康自检）
     */
    @SaCheckPermission("ai:health:query")
    @GetMapping("/checklist")
    public R<AiOnboardingReportVo> checklist() {
        return R.ok(onboardingService.checklist());
    }

}
