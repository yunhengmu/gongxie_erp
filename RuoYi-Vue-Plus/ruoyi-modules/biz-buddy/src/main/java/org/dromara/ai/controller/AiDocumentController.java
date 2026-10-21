package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiDocumentBatchBo;
import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkContextVo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.domain.vo.AiDocumentBatchStatusVo;
import org.dromara.ai.domain.vo.AiDocumentBatchVo;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.ai.service.IAiDocumentService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * AI 知识库文档 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/kb/doc")
public class AiDocumentController extends BaseController {

    private final IAiDocumentService aiDocumentService;

    private final IAiVectorService aiVectorService;

    /**
     * 查询文档列表
     */
    @SaCheckPermission("ai:kb:doc")
    @GetMapping("/list")
    public R<PageResult<AiDocumentVo>> list(@Validated(QueryGroup.class) AiDocumentBo bo, PageQuery pageQuery) {
        return R.ok(aiDocumentService.queryPageList(bo, pageQuery));
    }

    /**
     * 获取文档详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:kb:doc")
    @GetMapping("/{id}")
    public R<AiDocumentVo> getInfo(@NotNull(message = "主键不能为空")
                                   @PathVariable("id") Long id) {
        return R.ok(aiDocumentService.queryById(id));
    }

    /**
     * 查询文档解析后的纯文本（在线预览）
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:kb:doc:preview")
    @GetMapping("/{id}/content")
    public R<String> content(@NotNull(message = "主键不能为空")
                             @PathVariable("id") Long id) {
        // 注意：必须用 R.data，R.ok(String) 是「自定义提示信息」重载，会把文本放进 msg
        return R.data(aiDocumentService.queryContent(id));
    }

    /**
     * 分页查询文档切片
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:kb:doc")
    @GetMapping("/{id}/chunks")
    public R<PageResult<AiChunkVo>> chunks(@NotNull(message = "主键不能为空")
                                           @PathVariable("id") Long id, PageQuery pageQuery) {
        return R.ok(aiDocumentService.queryChunkPage(id, pageQuery));
    }

    /**
     * 按切片序号定位切片（回答「参考来源」点击用）
     *
     * @param id         文档主键
     * @param chunkIndex 切片序号（从 0 开始）
     * @param radius     前后各取多少条相邻切片（默认 1，上限 3）
     */
    @SaCheckPermission("ai:kb:doc")
    @GetMapping("/{id}/chunks/{chunkIndex}")
    public R<AiChunkContextVo> chunkContext(@NotNull(message = "主键不能为空")
                                            @PathVariable("id") Long id,
                                            @PathVariable("chunkIndex") Integer chunkIndex,
                                            @RequestParam(value = "radius", required = false) Integer radius) {
        return R.ok(aiDocumentService.queryChunkContext(id, chunkIndex, radius));
    }

    /**
     * 登记文档（文件已上传至对象存储）
     */
    @SaCheckPermission("ai:kb:doc:upload")
    @Log(title = "AI知识库文档", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Long> add(@Validated(AddGroup.class) @RequestBody AiDocumentBo bo) {
        aiDocumentService.insertByBo(bo);
        // 返回新文档主键：前端「上传后自动解析并向量化」需要立即拿到 id 提交批量入库
        return R.ok(bo.getId());
    }

    /**
     * 解析文档（提取文本 + 重建切片）
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:kb:doc:parse")
    @Log(title = "AI知识库文档", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/parse")
    public R<AiDocumentVo> parse(@NotNull(message = "主键不能为空")
                                 @PathVariable("id") Long id) {
        return R.ok(aiDocumentService.parse(id));
    }

    /**
     * 向量化文档（切片写入向量库，重复执行即重建索引）
     *
     * <p>不使用 {@code @RepeatSubmit}：重建索引本就需要重复执行，
     * 并发控制由文档自身的「向量化中」状态守卫承担。</p>
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:kb:doc:vector")
    @Log(title = "AI知识库文档", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/vectorize")
    public R<AiDocumentVo> vectorize(@NotNull(message = "主键不能为空")
                                     @PathVariable("id") Long id) {
        return R.ok(aiVectorService.vectorizeDocument(id));
    }

    /**
     * 批量解析（异步）：立即返回受理与跳过明细，页面轮询 {@link #batchStatus} 获取进度。
     */
    @SaCheckPermission("ai:kb:doc:parse")
    @Log(title = "AI知识库文档批量解析", businessType = BusinessType.UPDATE)
    @PostMapping("/batch/parse")
    public R<AiDocumentBatchVo> batchParse(@Validated @RequestBody AiDocumentBatchBo bo) {
        return R.ok(aiDocumentService.submitParseBatch(bo.getIds()));
    }

    /**
     * 批量向量化（异步）：仅受理已解析完成的文档。
     */
    @SaCheckPermission("ai:kb:doc:vector")
    @Log(title = "AI知识库文档批量向量化", businessType = BusinessType.UPDATE)
    @PostMapping("/batch/vectorize")
    public R<AiDocumentBatchVo> batchVectorize(@Validated @RequestBody AiDocumentBatchBo bo) {
        return R.ok(aiDocumentService.submitVectorizeBatch(bo.getIds()));
    }

    /**
     * 批量一键入库（异步）：逐文档先解析、成功后串接向量化。
     */
    @SaCheckPermission(value = {"ai:kb:doc:parse", "ai:kb:doc:vector"}, mode = SaMode.AND)
    @Log(title = "AI知识库文档批量入库", businessType = BusinessType.UPDATE)
    @PostMapping("/batch/ingest")
    public R<AiDocumentBatchVo> batchIngest(@Validated @RequestBody AiDocumentBatchBo bo) {
        return R.ok(aiDocumentService.submitIngestBatch(bo.getIds()));
    }

    /**
     * 查询批量处理进度（轻量回执，供页面轮询）
     *
     * @param ids 文档主键串（逗号分隔）
     */
    @SaCheckPermission("ai:kb:doc")
    @GetMapping("/batch/status")
    public R<List<AiDocumentBatchStatusVo>> batchStatus(@RequestParam("ids") List<Long> ids) {
        return R.ok(aiDocumentService.queryBatchStatus(ids));
    }

    /**
     * 删除文档
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:kb:doc:remove")
    @Log(title = "AI知识库文档", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiDocumentService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

}