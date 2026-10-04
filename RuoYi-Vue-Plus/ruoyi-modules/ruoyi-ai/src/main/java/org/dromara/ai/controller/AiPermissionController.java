package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiPermissionModeStore;
import org.dromara.ai.domain.bo.AiPermissionDecisionBo;
import org.dromara.ai.domain.vo.AiPermissionDecisionVo;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 工具权限 Controller（REQ-AI-23 / 27）
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/permission")
public class AiPermissionController extends BaseController {

    private final IAiPermissionDecisionService decisionService;

    private final AiPermissionModeStore permissionModeStore;

    /**
     * 分页查询权限决策日志
     */
    @SaCheckPermission("ai:perm:list")
    @GetMapping("/decision/list")
    public R<PageResult<AiPermissionDecisionVo>> decisionList(@Validated(QueryGroup.class) AiPermissionDecisionBo bo,
                                                              PageQuery pageQuery) {
        return R.ok(decisionService.queryPage(bo, pageQuery));
    }

    /**
     * 查询当前全局权限模式
     */
    @SaCheckPermission("ai:perm:list")
    @GetMapping("/mode")
    public R<String> getMode() {
        // 注意：必须用 R.data，R.ok(String) 是「自定义提示信息」重载，会把模式名放进 msg，导致前端读 data 取到 null
        return R.data(permissionModeStore.resolve().name());
    }

    /**
     * 切换全局权限模式（运行期生效，重启后仍保留）
     *
     * @param mode AgentScope PermissionMode 名称
     */
    @SaCheckPermission("ai:perm:mode")
    @Log(title = "AI工具权限模式", businessType = BusinessType.UPDATE)
    @PutMapping("/mode")
    public R<Void> updateMode(@NotBlank(message = "权限模式不能为空") @RequestParam String mode) {
        permissionModeStore.update(mode);
        return R.ok();
    }

}