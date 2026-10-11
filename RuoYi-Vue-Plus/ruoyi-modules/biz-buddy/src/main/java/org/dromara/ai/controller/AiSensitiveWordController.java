package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiSensitiveWordBo;
import org.dromara.ai.domain.vo.AiSensitiveWordVo;
import org.dromara.ai.service.IAiSensitiveWordService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
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

/**
 * AI 敏感词库 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/safety/word")
public class AiSensitiveWordController extends BaseController {

    private final IAiSensitiveWordService sensitiveWordService;

    /**
     * 查询敏感词列表
     */
    @SaCheckPermission("ai:safety:word:list")
    @GetMapping("/list")
    public R<PageResult<AiSensitiveWordVo>> list(@Validated(QueryGroup.class) AiSensitiveWordBo bo,
                                                 PageQuery pageQuery) {
        return R.ok(sensitiveWordService.queryPage(bo, pageQuery));
    }

    /**
     * 获取敏感词详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:safety:word:query")
    @GetMapping("/{id}")
    public R<AiSensitiveWordVo> getInfo(@NotNull(message = "主键不能为空")
                                        @PathVariable("id") Long id) {
        return R.ok(sensitiveWordService.queryById(id));
    }

    /**
     * 新增敏感词
     */
    @SaCheckPermission("ai:safety:word:add")
    @Log(title = "AI敏感词库", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated @RequestBody AiSensitiveWordBo bo) {
        return toAjax(sensitiveWordService.insert(bo));
    }

    /**
     * 修改敏感词
     */
    @SaCheckPermission("ai:safety:word:edit")
    @Log(title = "AI敏感词库", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated @RequestBody AiSensitiveWordBo bo) {
        return toAjax(sensitiveWordService.update(bo));
    }

    /**
     * 删除敏感词
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:safety:word:remove")
    @Log(title = "AI敏感词库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(sensitiveWordService.deleteByIds(Arrays.asList(ids)));
    }

}