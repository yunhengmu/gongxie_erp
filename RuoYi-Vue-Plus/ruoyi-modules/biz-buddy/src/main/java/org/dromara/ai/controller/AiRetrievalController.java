package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiRetrievalContextAssembler;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 知识库检索测试 Controller
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/kb/retrieval")
public class AiRetrievalController extends BaseController {

    private final IAiVectorService aiVectorService;

    private final IAiKnowledgeBaseService aiKnowledgeBaseService;

    private final AiProperties aiProperties;

    /**
     * 检索测试：按查询文本召回切片，用于上线前验证问答质量
     *
     * <p>按知识库生效范围校验：对当前用户不可见的知识库直接拒绝，避免绕过范围直接检索。</p>
     *
     * @param kbId      知识库主键
     * @param query     查询文本
     * @param topK      召回条数，为空取知识库配置
     * @param threshold 相似度阈值，为空取知识库配置
     * @param rerank    是否开启重排，为空时跟随平台配置（{@code bizbuddy.ai.rag.rerank-enabled}）
     * @param assemble  是否对命中应用上下文装配（P0-③ 去重 + 相邻合并 + 字符上限）；为空 / false 时保持原始命中
     * @param rerankMode 重排融合模式（{@code replace} / {@code rank_rrf} / {@code score_fusion}），
     *                   为空时跟随平台配置（{@code bizbuddy.ai.rag.rerank-mode}）；仅 {@code rerank=true} 时生效
     */
    @SaCheckPermission("ai:kb:retrieval")
    @Log(title = "AI检索测试", businessType = BusinessType.OTHER)
    @GetMapping("/test")
    public R<List<AiRetrievalHitVo>> test(@NotNull(message = "知识库不能为空") @RequestParam Long kbId,
                                          @NotBlank(message = "查询内容不能为空") @RequestParam String query,
                                          @Min(value = 1, message = "召回条数不能小于 1")
                                          @Max(value = 50, message = "召回条数不能大于 50")
                                          @RequestParam(required = false) Integer topK,
                                          @DecimalMin(value = "0.000", message = "相似度阈值不能小于 0")
                                          @DecimalMax(value = "1.000", message = "相似度阈值不能大于 1")
                                          @RequestParam(required = false) BigDecimal threshold,
                                          @RequestParam(required = false) Boolean rerank,
                                          @RequestParam(required = false) Boolean assemble,
                                          @RequestParam(required = false) String rerankMode) {
        aiKnowledgeBaseService.validateVisible(kbId, LoginHelper.getUserId(), "检索");
        boolean useRerank = rerank != null ? rerank : aiProperties.getRag().isRerankEnabled();
        List<AiRetrievalHitVo> hits = aiVectorService.retrieve(kbId, query, topK, threshold, useRerank, rerankMode);
        if (Boolean.TRUE.equals(assemble)) {
            return R.ok(AiRetrievalContextAssembler.assemble(hits,
                AiRetrievalContextAssembler.Options.from(aiProperties.getRag())));
        }
        return R.ok(hits);
    }

}