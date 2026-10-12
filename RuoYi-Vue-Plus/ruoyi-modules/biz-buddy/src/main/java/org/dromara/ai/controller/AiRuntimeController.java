package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiRouteBudgetConfigBo;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.ai.domain.vo.AiJevConfigVo;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
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
 * <p>结构化决策路由分组（{@code /ai/runtime/jev}）在页面上与「安全合规配置」同页展示，
 * 故<b>复用其 query / edit 权限</b>（对齐 docs/21 §8「无新增菜单与权限」的口径），不新增权限串。</p>
 *
 * @author BizBuddy
 */
@Slf4j
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

    /**
     * 查询当前生效的编排预算配置
     */
    @SaCheckPermission("ai:runtime:route-budget:query")
    @GetMapping("/route-budget")
    public R<AiRouteBudgetConfigBo> getRouteBudget() {
        return R.ok(runtimeConfigStore.routeBudget());
    }

    /**
     * 保存编排预算配置（运行期生效，下一次编排即按新预算执行）
     */
    @SaCheckPermission("ai:runtime:route-budget:edit")
    @Log(title = "AI编排预算配置", businessType = BusinessType.UPDATE)
    @PutMapping("/route-budget")
    public R<Void> updateRouteBudget(@Validated @RequestBody AiRouteBudgetConfigBo bo) {
        runtimeConfigStore.updateRouteBudget(bo);
        return R.ok();
    }

    /**
     * 查询当前生效的安全合规配置（脱敏 + 内容安全）
     */
    @SaCheckPermission("ai:runtime:security:query")
    @GetMapping("/security")
    public R<AiSecurityConfigBo> getSecurity() {
        return R.ok(runtimeConfigStore.security());
    }

    /**
     * 保存安全合规配置（运行期生效，下一次对话 / 编排即按新策略执行）
     */
    @SaCheckPermission("ai:runtime:security:edit")
    @Log(title = "AI安全合规配置", businessType = BusinessType.UPDATE)
    @PutMapping("/security")
    public R<Void> updateSecurity(@Validated @RequestBody AiSecurityConfigBo bo) {
        runtimeConfigStore.updateSecurity(bo);
        return R.ok();
    }

    /**
     * 查询当前生效的结构化决策路由配置（不回显密钥，只返回是否已配置）
     */
    @SaCheckPermission("ai:runtime:security:query")
    @GetMapping("/jev")
    public R<AiJevConfigVo> getJev() {
        return R.ok(toJevVo(runtimeConfigStore.jev()));
    }

    /**
     * 保存结构化决策路由配置（运行期生效，下一次编排即按新模式 / 阈值执行）
     *
     * <p>密钥留空表示<b>不修改已保存的密钥</b>（与「模型配置」口径一致）：为空时回填当前值再保存。</p>
     */
    @SaCheckPermission("ai:runtime:security:edit")
    @Log(title = "AI结构化决策配置", businessType = BusinessType.UPDATE)
    @PutMapping("/jev")
    public R<Void> updateJev(@Validated @RequestBody AiJevConfigBo bo) {
        if (StringUtils.isBlank(bo.getApiKey())) {
            // 留空 = 不修改密钥：沿用当前已保存值，避免误清空导致半残状态（enabled=true 但无密钥）
            bo.setApiKey(runtimeConfigStore.jev().getApiKey());
        }
        // 不打印密钥，只记录定位问题需要的字段
        log.info("保存结构化决策配置：enabled={}, mode={}, baseUrl={}, model={}, threshold={}, apiKey={}",
            bo.isEnabled(), bo.getMode(), bo.getBaseUrl(), bo.getModel(), bo.getConfidenceThreshold(),
            StringUtils.isBlank(bo.getApiKey()) ? "未配置" : "已配置");
        runtimeConfigStore.updateJev(bo);
        return R.ok();
    }

    /**
     * 把业务对象映射为视图对象：不回显密钥，只暴露「是否已配置」。
     *
     * @param bo 结构化决策配置
     * @return 视图对象
     */
    private AiJevConfigVo toJevVo(AiJevConfigBo bo) {
        AiJevConfigVo vo = new AiJevConfigVo();
        vo.setEnabled(bo.isEnabled());
        vo.setBaseUrl(bo.getBaseUrl());
        vo.setModel(bo.getModel());
        vo.setTimeoutSeconds(bo.getTimeoutSeconds());
        vo.setMode(bo.getMode());
        vo.setConfidenceThreshold(bo.getConfidenceThreshold());
        vo.setFailOpen(bo.isFailOpen());
        vo.setStateMaxChars(bo.getStateMaxChars());
        vo.setPersistProbabilities(bo.isPersistProbabilities());
        vo.setApiKeyConfigured(StringUtils.isNotBlank(bo.getApiKey()));
        return vo;
    }

}