package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiSafetyEventBo;
import org.dromara.ai.domain.vo.AiSafetyEventVo;
import org.dromara.ai.service.IAiSafetyEventService;
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
 * AI 安全事件 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/safety/event")
public class AiSafetyEventController extends BaseController {

    private final IAiSafetyEventService safetyEventService;

    /**
     * 查询安全事件列表
     */
    @SaCheckPermission("ai:safety:event:list")
    @GetMapping("/list")
    public R<PageResult<AiSafetyEventVo>> list(@Validated(QueryGroup.class) AiSafetyEventBo bo,
                                               PageQuery pageQuery) {
        return R.ok(safetyEventService.queryPage(bo, pageQuery));
    }

    /**
     * 获取安全事件详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:safety:event:query")
    @GetMapping("/{id}")
    public R<AiSafetyEventVo> getInfo(@NotNull(message = "主键不能为空")
                                      @PathVariable("id") Long id) {
        return R.ok(safetyEventService.queryById(id));
    }

}