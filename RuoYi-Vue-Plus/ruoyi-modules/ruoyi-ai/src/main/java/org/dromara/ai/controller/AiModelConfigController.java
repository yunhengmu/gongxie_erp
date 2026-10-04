package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiModelConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiModelTestVo;
import org.dromara.ai.service.IAiModelConfigService;
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
 * AI 模型配置 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/model")
public class AiModelConfigController extends BaseController {

    private final IAiModelConfigService aiModelConfigService;

    /**
     * 查询模型配置列表
     */
    @SaCheckPermission("ai:model:list")
    @GetMapping("/list")
    public R<PageResult<AiModelConfigVo>> list(@Validated(QueryGroup.class) AiModelConfigBo bo, PageQuery pageQuery) {
        return R.ok(aiModelConfigService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询启用中的模型选项（供知识库选择向量模型）
     *
     * <p>选项接口，被三个管理页直接消费：智能体定义页（对话模型）、知识库页（向量模型）、
     * 子智能体页（对话模型）。故按「能进对应页面即可读选项」的既有口径放行（与
     * {@code /ai/agent/tool/options}、{@code /ai/kb/visible-options} 一致，任一权限即可）；
     * 返回体不含密钥明文（{@code apiKey} 标注 {@code @JsonIgnore}，仅回掩码）。</p>
     *
     * @param modelType 模型类型，为空表示不限
     */
    @SaCheckPermission(value = {"ai:model:list", "ai:agent:list", "ai:agent:entry:query", "ai:kb:list", "ai:subagent:list"},
        mode = SaMode.OR)
    @GetMapping("/options")
    public R<List<AiModelConfigVo>> options(@RequestParam(required = false) String modelType) {
        return R.ok(aiModelConfigService.queryOptions(modelType));
    }

    /**
     * 获取模型配置详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:model:query")
    @GetMapping("/{id}")
    public R<AiModelConfigVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable("id") Long id) {
        return R.ok(aiModelConfigService.queryById(id));
    }

    /**
     * 新增模型配置
     */
    @SaCheckPermission("ai:model:add")
    @Log(title = "AI模型配置", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiModelConfigBo bo) {
        return toAjax(aiModelConfigService.insertByBo(bo));
    }

    /**
     * 修改模型配置
     */
    @SaCheckPermission("ai:model:edit")
    @Log(title = "AI模型配置", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiModelConfigBo bo) {
        return toAjax(aiModelConfigService.updateByBo(bo));
    }

    /**
     * 复制模型配置（按源配置整行复制：密钥密文与状态原样复制，名称自动追加 copy）
     *
     * @param id 源配置主键
     */
    @SaCheckPermission("ai:model:copy")
    @Log(title = "AI模型配置", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/copy/{id}")
    public R<AiModelConfigVo> copy(@NotNull(message = "主键不能为空")
                                   @PathVariable("id") Long id) {
        return R.ok(aiModelConfigService.copyById(id));
    }

    /**
     * 删除模型配置
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:model:remove")
    @Log(title = "AI模型配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiModelConfigService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

    /**
     * 模型连通性测试（不落库）
     */
    @SaCheckPermission("ai:model:test")
    @PostMapping("/test")
    public R<AiModelTestVo> test(@RequestBody AiModelConfigBo bo) {
        return R.ok(aiModelConfigService.testConnection(bo));
    }

}