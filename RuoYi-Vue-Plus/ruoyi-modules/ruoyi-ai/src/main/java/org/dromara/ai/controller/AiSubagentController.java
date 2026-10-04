package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiSubagentBo;
import org.dromara.ai.domain.vo.AiSubagentOptionVo;
import org.dromara.ai.domain.vo.AiSubagentVo;
import org.dromara.ai.service.IAiSubagentService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * AI 子智能体 Controller（REQ-AI-04）
 *
 * <p>子智能体是平台级可复用定义，与智能体为多对多绑定；工具白名单只允许只读工具
 * （子智能体侧没有人工确认闸门，写入类工具不得下放）。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/subagent")
public class AiSubagentController extends BaseController {

    private final IAiSubagentService aiSubagentService;

    /**
     * 查询子智能体列表（管理页全量）
     */
    @SaCheckPermission("ai:subagent:list")
    @GetMapping("/list")
    public R<PageResult<AiSubagentVo>> list(@Validated(QueryGroup.class) AiSubagentBo bo, PageQuery pageQuery) {
        return R.ok(aiSubagentService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询启用中的子智能体选项（供智能体定义页绑定子智能体）
     */
    @SaCheckPermission("ai:subagent:list")
    @GetMapping("/options")
    public R<List<AiSubagentOptionVo>> options() {
        return R.ok(aiSubagentService.queryOptions());
    }

    /**
     * 获取子智能体详情
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:subagent:query")
    @GetMapping("/{id}")
    public R<AiSubagentVo> getInfo(@NotNull(message = "主键不能为空")
                                   @PathVariable("id") Long id) {
        return R.ok(aiSubagentService.queryById(id));
    }

    /**
     * 新增子智能体
     */
    @SaCheckPermission("ai:subagent:add")
    @Log(title = "AI子智能体", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiSubagentBo bo) {
        return toAjax(aiSubagentService.insertByBo(bo));
    }

    /**
     * 修改子智能体（子智能体标识不可修改）
     */
    @SaCheckPermission("ai:subagent:edit")
    @Log(title = "AI子智能体", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiSubagentBo bo) {
        return toAjax(aiSubagentService.updateByBo(bo));
    }

    /**
     * 删除子智能体（物理删除；被智能体绑定时拒绝）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:subagent:remove")
    @Log(title = "AI子智能体", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiSubagentService.deleteWithValidByIds(Arrays.asList(ids)));
    }

}