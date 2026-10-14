package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计与运行数据保留策略 Controller。
 *
 * <p>挂在「编排运行」页：查询复用该页的 {@code ai:audit:route:query}，保存与清理各用独立按钮权限。
 * 清理为人工触发、默认为 0 天（不清理），不引入自动删除。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/audit/retention")
public class AiAuditRetentionController extends BaseController {

    private final IAiAuditRetentionService retentionService;

    /**
     * 查询当前保留策略与各表超期行数
     */
    @SaCheckPermission("ai:audit:route:query")
    @GetMapping
    public R<AiAuditRetentionVo> getRetention() {
        return R.ok(retentionService.getRetention());
    }

    /**
     * 保存保留策略（运行期生效，下一次清理即按新天数执行）
     */
    @SaCheckPermission("ai:audit:retention:edit")
    @Log(title = "审计数据保留策略", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> saveRetention(@Validated @RequestBody AiAuditRetentionBo bo) {
        retentionService.saveRetention(bo);
        return R.ok();
    }

    /**
     * 按当前保留策略清理超期数据（物理删除，返回逐表删除行数）
     */
    @SaCheckPermission("ai:audit:retention:cleanup")
    @Log(title = "审计数据清理", businessType = BusinessType.CLEAN)
    @PostMapping("/purge")
    public R<AiAuditRetentionVo> purge() {
        return R.ok(retentionService.purge());
    }

}
