package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 运行期策略 Controller（REQ-AI-21 上下文压缩 / REQ-AI-22 计划模式与任务清单）
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/runtime")
public class AiRuntimeController extends BaseController {

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 查询当前生效的上下文压缩策略
     */
    @SaCheckPermission("ai:runtime:compaction:query")
    @GetMapping("/compaction")
    public R<AiCompactionConfigBo> getCompaction() {
        return R.ok(runtimeConfigStore.compaction());
    }

    /**
     * 保存上下文压缩策略（运行期生效，重启后仍保留）
     */
    @SaCheckPermission("ai:runtime:compaction:edit")
    @Log(title = "AI上下文压缩策略", businessType = BusinessType.UPDATE)
    @PutMapping("/compaction")
    public R<Void> updateCompaction(@Validated @RequestBody AiCompactionConfigBo bo) {
        runtimeConfigStore.updateCompaction(bo);
        return R.ok();
    }

    /**
     * 查询当前生效的计划模式与任务清单配置
     */
    @SaCheckPermission("ai:runtime:plan:query")
    @GetMapping("/plan")
    public R<AiPlanConfigBo> getPlan() {
        return R.ok(runtimeConfigStore.plan());
    }

    /**
     * 保存计划模式与任务清单配置（运行期生效，重启后仍保留）
     */
    @SaCheckPermission("ai:runtime:plan:edit")
    @Log(title = "AI计划模式配置", businessType = BusinessType.UPDATE)
    @PutMapping("/plan")
    public R<Void> updatePlan(@Validated @RequestBody AiPlanConfigBo bo) {
        runtimeConfigStore.updatePlan(bo);
        return R.ok();
    }

}