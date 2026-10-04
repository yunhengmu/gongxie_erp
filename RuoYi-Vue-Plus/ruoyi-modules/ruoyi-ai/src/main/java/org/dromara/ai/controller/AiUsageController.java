package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiUsageStatBo;
import org.dromara.ai.domain.vo.AiUsageOverviewVo;
import org.dromara.ai.domain.vo.AiUsageStatExportVo;
import org.dromara.ai.domain.vo.AiUsageStatVo;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 用量与配额 Controller（REQ-AI-07）
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/usage")
public class AiUsageController extends BaseController {

    private final IAiUsageStatService usageStatService;

    /**
     * 分页查询用量明细
     */
    @SaCheckPermission("ai:usage:list")
    @GetMapping("/list")
    public R<PageResult<AiUsageStatVo>> list(@Validated(QueryGroup.class) AiUsageStatBo bo, PageQuery pageQuery) {
        return R.ok(usageStatService.queryPage(bo, pageQuery));
    }

    /**
     * 用量总览（合计 + 按日期 / 智能体 / 模型分组）
     */
    @SaCheckPermission("ai:usage:list")
    @GetMapping("/overview")
    public R<AiUsageOverviewVo> overview(@Validated(QueryGroup.class) AiUsageStatBo bo) {
        return R.ok(usageStatService.overview(bo));
    }

    /**
     * 导出用量明细（按当前筛选条件）
     */
    @SaCheckPermission("ai:usage:export")
    @Log(title = "AI用量统计", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@Validated(QueryGroup.class) AiUsageStatBo bo, HttpServletResponse response) {
        List<AiUsageStatExportVo> list = usageStatService.buildExportData(bo);
        ExcelBuilder.of(list, AiUsageStatExportVo.class).sheetName("用量统计").toResponse(response);
    }

}