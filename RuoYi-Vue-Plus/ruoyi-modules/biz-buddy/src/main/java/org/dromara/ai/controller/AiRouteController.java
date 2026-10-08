package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiRouteRunBo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiRouteRunStatsVo;
import org.dromara.ai.domain.vo.AiRouteRunVo;
import org.dromara.ai.service.IAiRouteService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 编排运行 Controller（REQ-AI-30 Phase 2 第三项）
 *
 * <p>只读接口：编排运行由编排链路在执行期写入（{@code AiRouteRecorder}），管理页仅查询与展示
 * ——列表（跨会话、可筛专家 / 动作 / 状态 / 时间范围）、统计（降级率 / 路由命中率 / 耗时与 Token）
 * 与详情（决策原文、任务片段、每次专家调用的契约与原文）。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/route")
public class AiRouteController extends BaseController {

    private final IAiRouteService aiRouteService;

    /**
     * 查询编排运行列表
     */
    @SaCheckPermission("ai:audit:route")
    @GetMapping("/run/list")
    public R<PageResult<AiRouteRunVo>> list(@Validated(QueryGroup.class) AiRouteRunBo bo, PageQuery pageQuery) {
        return R.ok(aiRouteService.queryPageList(bo, pageQuery));
    }

    /**
     * 编排运行统计（与列表同一筛选条件）
     */
    @SaCheckPermission("ai:audit:route")
    @GetMapping("/run/stats")
    public R<AiRouteRunStatsVo> stats(@Validated(QueryGroup.class) AiRouteRunBo bo) {
        return R.ok(aiRouteService.queryStats(bo));
    }

    /**
     * 获取编排运行详情（含全部专家调用明细与原始输出原文）
     *
     * @param id 编排主键
     */
    @SaCheckPermission("ai:audit:route:query")
    @GetMapping("/run/{id}")
    public R<AiRouteRunDetailVo> getInfo(@NotNull(message = "主键不能为空")
                                         @PathVariable("id") Long id) {
        return R.ok(aiRouteService.queryById(id));
    }

}