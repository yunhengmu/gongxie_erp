package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.document.AiBm25IndexRunner;
import org.dromara.ai.domain.bo.AiKnowledgeBaseBo;
import org.dromara.ai.domain.vo.AiKnowledgeBaseVo;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
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
 * AI 知识库 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/kb")
public class AiKnowledgeBaseController extends BaseController {

    private final IAiKnowledgeBaseService aiKnowledgeBaseService;

    private final AiBm25IndexRunner bm25IndexRunner;

    /**
     * 查询知识库列表
     */
    @SaCheckPermission("ai:kb:list")
    @GetMapping("/list")
    public R<PageResult<AiKnowledgeBaseVo>> list(@Validated(QueryGroup.class) AiKnowledgeBaseBo bo, PageQuery pageQuery) {
        return R.ok(aiKnowledgeBaseService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询启用中的知识库选项（供文档与切片页选择：该页属管理视角，展示全量）
     */
    @SaCheckPermission("ai:kb:list")
    @GetMapping("/options")
    public R<List<AiKnowledgeBaseVo>> options() {
        return R.ok(aiKnowledgeBaseService.queryOptions());
    }

    /**
     * 查询当前用户「可见且启用」的知识库选项
     *
     * <p>生效范围口径与智能体一致（0 公开 / 1 本部门 / 2 仅本人）：供智能体定义页的「绑定知识库」
     * 与检索测试页使用，避免绑定或测试到自己生效范围之外的知识库。</p>
     */
    @SaCheckPermission(value = {"ai:kb:list", "ai:agent:list", "ai:kb:retrieval"}, mode = SaMode.OR)
    @GetMapping("/visible-options")
    public R<List<AiKnowledgeBaseVo>> visibleOptions() {
        return R.ok(aiKnowledgeBaseService.queryVisibleOptions(LoginHelper.getUserId()));
    }

    /**
     * 获取知识库详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:kb:query")
    @GetMapping("/{id}")
    public R<AiKnowledgeBaseVo> getInfo(@NotNull(message = "主键不能为空")
                                        @PathVariable("id") Long id) {
        return R.ok(aiKnowledgeBaseService.queryById(id));
    }

    /**
     * 新增知识库
     */
    @SaCheckPermission("ai:kb:add")
    @Log(title = "AI知识库", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiKnowledgeBaseBo bo) {
        return toAjax(aiKnowledgeBaseService.insertByBo(bo));
    }

    /**
     * 修改知识库
     */
    @SaCheckPermission("ai:kb:edit")
    @Log(title = "AI知识库", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiKnowledgeBaseBo bo) {
        AiKnowledgeBaseVo before = bo.getId() == null ? null : aiKnowledgeBaseService.queryById(bo.getId());
        boolean updated = aiKnowledgeBaseService.updateByBo(bo);
        // 从纯向量切换为混合检索时异步重建 BM25 索引（不阻塞保存请求）
        if (updated && "hybrid".equals(bo.getRetrievalMode())
            && (before == null || !"hybrid".equals(before.getRetrievalMode()))) {
            bm25IndexRunner.rebuild(bo.getId());
        }
        return toAjax(updated);
    }

    /**
     * 重建知识库的 BM25 全文索引（切换为混合检索或索引失败后手动触发）。
     *
     * @param id 知识库主键
     */
    @SaCheckPermission("ai:kb:edit")
    @Log(title = "AI知识库BM25索引", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/bm25/rebuild")
    public R<Void> rebuildBm25(@NotNull(message = "主键不能为空") @PathVariable("id") Long id) {
        if (aiKnowledgeBaseService.queryById(id) == null) {
            throw new ServiceException("知识库不存在或已被删除");
        }
        bm25IndexRunner.rebuild(id);
        return R.ok();
    }

    /**
     * 删除知识库
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:kb:remove")
    @Log(title = "AI知识库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiKnowledgeBaseService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

}