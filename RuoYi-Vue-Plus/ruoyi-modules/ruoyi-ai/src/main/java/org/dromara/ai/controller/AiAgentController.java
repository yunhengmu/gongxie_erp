package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiEntryAgentBo;
import org.dromara.ai.domain.bo.AiExpertAgentBo;
import org.dromara.ai.domain.vo.AiAgentToolTrustVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiEntryAgentVo;
import org.dromara.ai.domain.vo.AiExpertAgentVo;
import org.dromara.ai.domain.vo.AiExpertVo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiToolService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * AI 智能体定义 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/agent")
public class AiAgentController extends BaseController {

    private final IAiAgentService aiAgentService;

    private final IAiToolService aiToolService;

    /**
     * 查询入口智能体（小Z，全平台唯一）
     */
    @SaCheckPermission(value = {"ai:agent:entry:query", "ai:agent:list"}, mode = SaMode.OR)
    @GetMapping("/entry")
    public R<AiEntryAgentVo> entry() {
        return R.ok(aiAgentService.queryEntryAgent());
    }

    /**
     * 修改入口智能体（小Z；只读写公共字段，不涉及工具/知识库/技能/子智能体绑定）
     */
    @SaCheckPermission(value = {"ai:agent:entry:edit", "ai:agent:edit"}, mode = SaMode.OR)
    @Log(title = "AI入口智能体配置", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/entry")
    public R<Void> updateEntry(@Validated(EditGroup.class) @RequestBody AiEntryAgentBo bo) {
        return toAjax(aiAgentService.updateEntryAgent(bo));
    }

    /**
     * 查询专家列表
     */
    @SaCheckPermission("ai:agent:list")
    @GetMapping("/list")
    public R<PageResult<AiExpertAgentVo>> list(@Validated(QueryGroup.class) AiExpertAgentBo bo, PageQuery pageQuery) {
        return R.ok(aiAgentService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询启用中的智能体选项（入口 + 全部启用专家，供管理面下拉选择）
     */
    @SaCheckPermission(value = {"ai:agent:list", "ai:chat:send"}, mode = SaMode.OR)
    @GetMapping("/options")
    public R<List<AiAgentVo>> options() {
        return R.ok(aiAgentService.queryOptions());
    }

    /**
     * 查询当前用户可见的**自动候选**专家清单（REQ-AI-30；管理面核对路由候选）
     *
     * <p>只含 {@code auto_route='1'} 的专家；「指定专家」下拉请用 {@code /experts}。</p>
     */
    @SaCheckPermission(value = {"ai:agent:list", "ai:chat:send"}, mode = SaMode.OR)
    @GetMapping("/experts/candidates")
    public R<List<AiExpertVo>> expertCandidates() {
        return R.ok(aiAgentService.queryExperts());
    }

    /**
     * 查询当前用户可见的**全部**专家清单（REQ-AI-33；供工作台「指定专家」下拉）
     *
     * <p>**不过滤** {@code auto_route}：{@code auto_route='0'} 的专家只能被用户手动指定，故必须可见。</p>
     */
    @SaCheckPermission(value = {"ai:agent:list", "ai:chat:send"}, mode = SaMode.OR)
    @GetMapping("/experts")
    public R<List<AiExpertVo>> experts() {
        return R.ok(aiAgentService.queryVisibleExperts());
    }

    /**
     * 查询可绑定的业务工具选项（供定义页绑定工具集）
     */
    @SaCheckPermission(value = {"ai:agent:list", "ai:tool:list"}, mode = SaMode.OR)
    @GetMapping("/tool/options")
    public R<List<AiToolVo>> toolOptions() {
        return R.ok(aiToolService.queryOptions());
    }

    /**
     * 获取智能体详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:agent:query")
    @GetMapping("/{id}")
    public R<AiAgentVo> getInfo(@NotNull(message = "主键不能为空")
                                @PathVariable("id") Long id) {
        return R.ok(aiAgentService.queryById(id));
    }

    /**
     * 查询该智能体已记住的工具（对话确认卡片选择「记住该工具」沉淀的免确认决策，按用户列出）
     *
     * @param id 智能体主键
     */
    @SaCheckPermission("ai:agent:query")
    @GetMapping("/{id}/trusted-tools")
    public R<List<AiAgentToolTrustVo>> trustedTools(@NotNull(message = "主键不能为空")
                                                    @PathVariable("id") Long id) {
        return R.ok(aiAgentService.queryTrustedTools(id));
    }

    /**
     * 撤销某条「记住该工具」信任决策（撤销后该用户的该工具恢复按配置档位判定）
     *
     * @param id       智能体主键
     * @param userId   记住该工具的用户主键
     * @param toolCode 工具编码
     */
    @SaCheckPermission("ai:agent:edit")
    @Log(title = "AI智能体工具信任决策", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}/trusted-tools")
    public R<Void> revokeTrustedTool(@NotNull(message = "主键不能为空") @PathVariable("id") Long id,
                                     @NotNull(message = "用户不能为空") @RequestParam("userId") Long userId,
                                     @NotBlank(message = "工具编码不能为空") @RequestParam("toolCode") String toolCode) {
        return toAjax(aiAgentService.revokeTrustedTool(id, userId, toolCode));
    }

    /**
     * 新增专家定义
     */
    @SaCheckPermission("ai:agent:add")
    @Log(title = "AI专家智能体定义", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiExpertAgentBo bo) {
        return toAjax(aiAgentService.insertByBo(bo));
    }

    /**
     * 修改专家定义
     */
    @SaCheckPermission("ai:agent:edit")
    @Log(title = "AI专家智能体定义", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiExpertAgentBo bo) {
        return toAjax(aiAgentService.updateByBo(bo));
    }

    /**
     * 删除专家定义
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:agent:remove")
    @Log(title = "AI专家智能体定义", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiAgentService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

}
