package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiToolBo;
import org.dromara.ai.domain.bo.AiToolTagBatchBo;
import org.dromara.ai.domain.bo.AiToolTagBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.ai.service.IAiToolService;
import org.dromara.ai.service.IAiToolTagService;
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
 * 业务工具注册 Controller
 *
 * <p>本页只管理工具的注册元数据与启停；工具的「实现」必须在代码侧存在
 * （{@code implName} 对应 {@code AiToolFactory} 中登记的 key），
 * 页面不允许以裸 SQL / 裸 Shell / 裸 HTTP 的方式新增可执行能力。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/tool")
public class AiToolController extends BaseController {

    private final IAiToolService aiToolService;

    private final IAiToolTagService aiToolTagService;

    /**
     * 查询工具列表
     */
    @SaCheckPermission("ai:tool:list")
    @GetMapping("/list")
    public R<PageResult<AiToolVo>> list(@Validated(QueryGroup.class) AiToolBo bo, PageQuery pageQuery) {
        return R.ok(aiToolService.queryPageList(bo, pageQuery));
    }

    /**
     * 获取工具详情
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:tool:list")
    @GetMapping("/{id}")
    public R<AiToolVo> getInfo(@NotNull(message = "主键不能为空")
                               @PathVariable("id") Long id) {
        return R.ok(aiToolService.queryById(id));
    }

    /**
     * 新增工具注册
     */
    @SaCheckPermission("ai:tool:add")
    @Log(title = "AI业务工具", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiToolBo bo) {
        return toAjax(aiToolService.insertByBo(bo));
    }

    /**
     * 修改工具注册
     */
    @SaCheckPermission("ai:tool:edit")
    @Log(title = "AI业务工具", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiToolBo bo) {
        return toAjax(aiToolService.updateByBo(bo));
    }

    /**
     * 删除工具注册
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:tool:remove")
    @Log(title = "AI业务工具", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiToolService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

    // -----------------------------------------------------------------
    //  工具标签（业务工具注册页内的标签管理；不新增菜单页，仅按钮权限）
    // -----------------------------------------------------------------

    /**
     * 查询标签列表（含使用该标签的工具数）
     */
    @SaCheckPermission("ai:tool:tag:query")
    @GetMapping("/tag/list")
    public R<PageResult<AiToolTagVo>> tagList(@Validated(QueryGroup.class) AiToolTagBo bo, PageQuery pageQuery) {
        return R.ok(aiToolTagService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询全部标签选项（含使用计数；供工具列表筛选与工具表单多选）
     */
    @SaCheckPermission("ai:tool:tag:query")
    @GetMapping("/tag/options")
    public R<List<AiToolTagVo>> tagOptions() {
        return R.ok(aiToolTagService.queryOptions());
    }

    /**
     * 新增标签
     */
    @SaCheckPermission("ai:tool:tag:add")
    @Log(title = "AI工具标签", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/tag")
    public R<Void> tagAdd(@Validated(AddGroup.class) @RequestBody AiToolTagBo bo) {
        return toAjax(aiToolTagService.insertByBo(bo));
    }

    /**
     * 修改标签
     */
    @SaCheckPermission("ai:tool:tag:edit")
    @Log(title = "AI工具标签", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/tag")
    public R<Void> tagEdit(@Validated(EditGroup.class) @RequestBody AiToolTagBo bo) {
        return toAjax(aiToolTagService.updateByBo(bo));
    }

    /**
     * 批量打标签（**追加**语义：为勾选的工具追加标签，工具已有标签不受影响）
     */
    @SaCheckPermission("ai:tool:tag:add")
    @Log(title = "AI工具标签", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/tag/apply")
    public R<Void> tagApplyBatch(@Validated @RequestBody AiToolTagBatchBo bo) {
        aiToolTagService.applyTagsToTools(bo.getToolIds(), bo.getTagIds());
        return R.ok();
    }

    /**
     * 批量移除标签（从勾选的工具上移除指定标签，工具的其他标签保留）
     */
    @SaCheckPermission("ai:tool:tag:remove")
    @Log(title = "AI工具标签", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/tag/remove")
    public R<Void> tagRemoveBatch(@Validated @RequestBody AiToolTagBatchBo bo) {
        aiToolTagService.removeTagsFromTools(bo.getToolIds(), bo.getTagIds());
        return R.ok();
    }

    /**
     * 删除标签（级联解除其与全部工具的关联）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:tool:tag:remove")
    @Log(title = "AI工具标签", businessType = BusinessType.DELETE)
    @DeleteMapping("/tag/{ids}")
    public R<Void> tagRemove(@NotEmpty(message = "主键不能为空")
                             @PathVariable Long[] ids) {
        return toAjax(aiToolTagService.deleteWithValidByIds(Arrays.asList(ids)));
    }

}
