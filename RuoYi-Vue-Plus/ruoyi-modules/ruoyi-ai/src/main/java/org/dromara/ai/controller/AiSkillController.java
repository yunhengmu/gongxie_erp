package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiSkillBo;
import org.dromara.ai.domain.vo.AiSkillOptionVo;
import org.dromara.ai.domain.vo.AiSkillVo;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.satoken.utils.LoginHelper;
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
 * AI 技能仓库 Controller
 *
 * <p>技能正文以 SKILL.md 落库（frontmatter 由技能标识与用途说明自动拼装），参考资料落
 * {@code ai_skill_resource}；技能与智能体为多对多绑定，装配时只放行被绑定的技能，
 * 运行期再按提问用户的生效范围过滤。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/skill")
public class AiSkillController extends BaseController {

    private final IAiSkillService aiSkillService;

    /**
     * 查询技能列表（管理页全量，含使用计数与陈旧归档状态）
     */
    @SaCheckPermission("ai:skill:list")
    @GetMapping("/list")
    public R<PageResult<AiSkillVo>> list(@Validated(QueryGroup.class) AiSkillBo bo, PageQuery pageQuery) {
        return R.ok(aiSkillService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询当前用户可见的启用中技能选项（供智能体定义页绑定技能）
     *
     * <p>选项接口，唯一消费方是智能体定义页，故按「能进定义页即可读技能选项」的口径放行
     * （任一权限即可，与 {@code /ai/agent/tool/options}、{@code /ai/kb/visible-options} 一致）。</p>
     */
    @SaCheckPermission(value = {"ai:skill:list", "ai:agent:list"}, mode = SaMode.OR)
    @GetMapping("/visible-options")
    public R<List<AiSkillOptionVo>> visibleOptions() {
        return R.ok(aiSkillService.queryVisibleOptions(LoginHelper.getUserId()));
    }

    /**
     * 获取技能详情（含参考资料明细）
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:skill:query")
    @GetMapping("/{id}")
    public R<AiSkillVo> getInfo(@NotNull(message = "主键不能为空")
                                @PathVariable("id") Long id) {
        return R.ok(aiSkillService.queryById(id));
    }

    /**
     * 新增技能
     */
    @SaCheckPermission("ai:skill:add")
    @Log(title = "AI技能仓库", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiSkillBo bo) {
        return toAjax(aiSkillService.insertByBo(bo));
    }

    /**
     * 修改技能（技能标识不可修改）
     */
    @SaCheckPermission("ai:skill:edit")
    @Log(title = "AI技能仓库", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiSkillBo bo) {
        return toAjax(aiSkillService.updateByBo(bo));
    }

    /**
     * 删除技能（物理删除，参考资料级联清理；被智能体绑定时拒绝）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:skill:remove")
    @Log(title = "AI技能仓库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiSkillService.deleteWithValidByIds(Arrays.asList(ids)));
    }

}
