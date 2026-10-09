package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiTraceBo;
import org.dromara.ai.domain.vo.AiTraceVo;
import org.dromara.ai.service.IAiTraceService;
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
 * AI 链路追踪 Controller（REQ-AI-28）
 *
 * <p>只读接口：链路由执行链路（实时对话 / HITL 恢复 / 定时任务）在执行期写入，
 * 管理页仅查询与展示，不提供修改与删除。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/trace")
public class AiTraceController extends BaseController {

    private final IAiTraceService aiTraceService;

    /**
     * 查询链路列表
     */
    @SaCheckPermission("ai:audit:trace")
    @GetMapping("/list")
    public R<PageResult<AiTraceVo>> list(@Validated(QueryGroup.class) AiTraceBo bo, PageQuery pageQuery) {
        return R.ok(aiTraceService.queryPageList(bo, pageQuery));
    }

    /**
     * 获取链路详情（含上下文清单与状态迁移时间线）
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:audit:trace:query")
    @GetMapping("/{id}")
    public R<AiTraceVo> getInfo(@NotNull(message = "主键不能为空")
                                @PathVariable("id") Long id) {
        return R.ok(aiTraceService.queryById(id));
    }

}