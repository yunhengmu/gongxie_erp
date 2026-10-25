package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiScenarioPackBo;
import org.dromara.ai.domain.vo.AiScenarioPackMcpOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackVo;
import org.dromara.ai.service.IAiScenarioPackService;
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
 * AI 场景包 Controller
 *
 * <p>场景包是「按角色 / 部门打包业务工具 + 外部 MCP 工具 + 技能」的可复用定义，
 * 用户在创建任务时选定并固化到会话，包内能力叠加到智能体既有绑定之上以增强对话。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/pack")
public class AiScenarioPackController extends BaseController {

    private final IAiScenarioPackService aiScenarioPackService;

    /**
     * 查询场景包列表（管理页全量）
     */
    @SaCheckPermission("ai:pack:list")
    @GetMapping("/list")
    public R<PageResult<AiScenarioPackVo>> list(@Validated(QueryGroup.class) AiScenarioPackBo bo,
                                                PageQuery pageQuery) {
        return R.ok(aiScenarioPackService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询可选场景包选项（创建任务时选择；按当前用户生效范围过滤）
     */
    @SaCheckPermission(value = {"ai:pack:list", "ai:chat:send"}, mode = SaMode.OR)
    @GetMapping("/options")
    public R<List<AiScenarioPackOptionVo>> options() {
        return R.ok(aiScenarioPackService.queryOptions());
    }

    /**
     * 查询外部 MCP 工具选项（管理页勾选用；取自启用中连接的工具白名单）
     */
    @SaCheckPermission(value = {"ai:pack:list", "ai:mcp:client:query"}, mode = SaMode.OR)
    @GetMapping("/mcp-options")
    public R<List<AiScenarioPackMcpOptionVo>> mcpToolOptions() {
        return R.ok(aiScenarioPackService.queryMcpToolOptions());
    }

    /**
     * 获取场景包详情（含三类绑定集合）
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:pack:query")
    @GetMapping("/{id}")
    public R<AiScenarioPackVo> getInfo(@NotNull(message = "主键不能为空")
                                       @PathVariable("id") Long id) {
        return R.ok(aiScenarioPackService.queryById(id));
    }

    /**
     * 新增场景包
     */
    @SaCheckPermission("ai:pack:add")
    @Log(title = "AI场景包", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiScenarioPackBo bo) {
        return toAjax(aiScenarioPackService.insertByBo(bo));
    }

    /**
     * 修改场景包（编码不可修改；三类绑定集合全量覆盖）
     */
    @SaCheckPermission("ai:pack:edit")
    @Log(title = "AI场景包", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiScenarioPackBo bo) {
        return toAjax(aiScenarioPackService.updateByBo(bo));
    }

    /**
     * 删除场景包（逻辑删除；历史会话保留快照）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:pack:remove")
    @Log(title = "AI场景包", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiScenarioPackService.deleteWithValidByIds(Arrays.asList(ids)));
    }

}