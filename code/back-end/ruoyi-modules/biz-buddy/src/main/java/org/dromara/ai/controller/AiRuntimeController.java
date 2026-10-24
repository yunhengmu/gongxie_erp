package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiRouteBudgetConfigBo;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.ai.domain.vo.AiJevConfigVo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiSecurityConfigVo;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
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

    /**
     * 密钥已配置时的掩码（对外只回带掩码，绝不下发明文）
     */
    private static final String KEY_MASK = "******";

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final IAiModelConfigService modelConfigService;

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
     *
     * <p>外部审核引擎密钥以掩码回显（{@code providerApiKeyMask}），不下发明文。</p>
     */
    @SaCheckPermission("ai:runtime:security:query")
    @GetMapping("/security")
    public R<AiSecurityConfigVo> getSecurity() {
        return R.ok(toSecurityVo(runtimeConfigStore.security()));
    }

    /**
     * 保存安全合规配置（运行期生效，下一次对话 / 编排即按新策略执行）
     */
    @SaCheckPermission("ai:runtime:security:edit")
    @Log(title = "AI安全合规配置", businessType = BusinessType.UPDATE)
    @PutMapping("/security")
    public R<Void> updateSecurity(@Validated @RequestBody AiSecurityConfigBo bo) {
        // 密钥留空表示不修改，由 store 沿用已存值（前端不回显密钥，始终以空值提交）
        runtimeConfigStore.updateSecurity(bo);
        return R.ok();
    }

    /**
     * 查询当前生效的结构化决策路由配置
     *
     * <p>决策模型的连接信息（服务地址 / 模型标识 / 密钥）统一由「模型配置」管理，故此处只回带
     * 所选模型的 {@code modelConfigId} 与判定策略。</p>
     */
    @SaCheckPermission("ai:runtime:jev:query")
    @GetMapping("/jev")
    public R<AiJevConfigVo> getJev() {
        return R.ok(toJevVo(runtimeConfigStore.jev()));
    }

    /**
     * 保存结构化决策路由配置（运行期生效，下一次编排即按新模式 / 阈值执行）
     *
     * <p><b>决策模型只能从「模型配置」里选</b>：开启时强校验所选模型「存在 + 启用 + decision 类型 +
     * 已配置密钥」，任一条不满足即报错，避免「打开了开关但没有可用决策模型」的半残状态。</p>
     */
    @SaCheckPermission("ai:runtime:jev:edit")
    @Log(title = "AI结构化决策配置", businessType = BusinessType.UPDATE)
    @PutMapping("/jev")
    public R<Void> updateJev(@Validated @RequestBody AiJevConfigBo bo) {
        validateDecisionModel(bo);
        log.info("保存结构化决策配置：enabled={}, modelConfigId={}, mode={}, timeout={}s, threshold={}",
            bo.isEnabled(), bo.getModelConfigId(), bo.getMode(), bo.getTimeoutSeconds(),
            bo.getConfidenceThreshold());
        runtimeConfigStore.updateJev(bo);
        return R.ok();
    }

    /**
     * 校验所选决策模型可用。
     *
     * @param bo 结构化决策配置
     */
    private void validateDecisionModel(AiJevConfigBo bo) {
        if (!bo.isEnabled()) {
            // 关闭态不校验：允许保留一个已选 id 供下次开启
            return;
        }
        Long modelConfigId = bo.getModelConfigId();
        if (modelConfigId == null) {
            throw new ServiceException("已开启结构化决策，请先选择一条决策模型"
                + "（可到「模型配置」新增，类型选「决策模型」）");
        }
        AiModelConfigVo model = modelConfigService.queryById(modelConfigId);
        if (model == null) {
            throw new ServiceException("所选决策模型不存在或已被删除，请重新选择");
        }
        if (!"0".equals(model.getStatus())) {
            throw new ServiceException("所选决策模型「" + model.getConfigName() + "」已停用，请启用后再保存");
        }
        if (!AiModelConfig.TYPE_DECISION.equalsIgnoreCase(model.getModelType())) {
            throw new ServiceException("所选模型「" + model.getConfigName() + "」不是决策模型（实际类型 "
                + model.getModelType() + "），请在「模型配置」中改选类型为「决策模型」的记录");
        }
        if (StringUtils.isBlank(modelConfigService.decryptApiKey(modelConfigId))) {
            throw new ServiceException("所选决策模型「" + model.getConfigName()
                + "」未配置 API 密钥，请先到「模型配置」补全后再保存");
        }
    }

    /**
     * 把业务对象映射为视图对象。
     *
     * @param bo 结构化决策配置
     * @return 视图对象
     */
    private AiJevConfigVo toJevVo(AiJevConfigBo bo) {
        AiJevConfigVo vo = new AiJevConfigVo();
        vo.setEnabled(bo.isEnabled());
        vo.setModelConfigId(bo.getModelConfigId());
        vo.setTimeoutSeconds(bo.getTimeoutSeconds());
        vo.setMode(bo.getMode());
        vo.setConfidenceThreshold(bo.getConfidenceThreshold());
        vo.setConfidenceThresholdMedium(bo.getConfidenceThresholdMedium());
        vo.setConfidenceThresholdLarge(bo.getConfidenceThresholdLarge());
        vo.setMarginRatio(bo.getMarginRatio());
        vo.setStateMaxChars(bo.getStateMaxChars());
        vo.setPersistProbabilities(bo.isPersistProbabilities());
        vo.setCircuitBreakerEnabled(bo.isCircuitBreakerEnabled());
        vo.setCircuitBreakerThreshold(bo.getCircuitBreakerThreshold());
        vo.setCircuitBreakerPauseSeconds(bo.getCircuitBreakerPauseSeconds());
        vo.setStickyRoutingEnabled(bo.isStickyRoutingEnabled());
        vo.setStickyMinConfidence(bo.getStickyMinConfidence());
        vo.setStickyMaxQueryLength(bo.getStickyMaxQueryLength());
        return vo;
    }

    /**
     * 把业务对象映射为视图对象：访问密钥**只转成掩码**，密文一律不进 VO。
     *
     * @param bo 安全合规配置
     * @return 视图对象
     */
    private AiSecurityConfigVo toSecurityVo(AiSecurityConfigBo bo) {
        AiSecurityConfigVo vo = new AiSecurityConfigVo();
        vo.setEnabled(bo.isEnabled());
        vo.setEngine(bo.getEngine());
        vo.setAction(bo.getAction());
        vo.setInputCheck(bo.isInputCheck());
        vo.setOutputCheck(bo.isOutputCheck());
        vo.setChannelOutCheck(bo.isChannelOutCheck());
        vo.setToolResultMask(bo.isToolResultMask());
        vo.setKbIngestCheck(bo.isKbIngestCheck());
        vo.setMcpOutCheck(bo.isMcpOutCheck());
        vo.setJevOutboundCheck(bo.isJevOutboundCheck());
        vo.setPromptHardening(bo.isPromptHardening());
        vo.setMaskPii(bo.isMaskPii());
        vo.setHoldBackChars(bo.getHoldBackChars());
        vo.setExcerptChars(bo.getExcerptChars());
        vo.setProviderEndpoint(bo.getProviderEndpoint());
        vo.setProviderApiKeyMask(StringUtils.isNotBlank(bo.getProviderApiKey()) ? KEY_MASK : "");
        return vo;
    }

}