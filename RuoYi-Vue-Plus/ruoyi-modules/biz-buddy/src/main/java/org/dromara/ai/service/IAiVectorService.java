package org.dromara.ai.service;

import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * AI 知识库向量化与检索 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiVectorService {

    /**
     * 向量化文档：为文档的全部切片生成向量并写入 Milvus；重复执行即为重建索引。
     *
     * @param docId 文档主键
     * @return 向量化后的文档视图对象
     */
    AiDocumentVo vectorizeDocument(Long docId);

    /**
     * 检索测试：按查询文本召回切片。
     *
     * @param kbId      知识库主键
     * @param query     查询文本
     * @param topK      召回条数，为空取知识库配置
     * @param threshold 相似度阈值，为空取知识库配置
     * @return 命中切片（按相似度降序）
     */
    List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold);

    /**
     * 检索测试：按查询文本召回切片，可选开启重排（Rerank）。
     *
     * <p>{@code rerank=true} 时先按 {@code max(topK, rerankTopN)} 稠密召回，再调重排模型重排后截取 topK；
     * 重排失败（未配密钥 / 非 2xx / 超时 / 结构异常）时保持原顺序与原文分数，不阻断检索。</p>
     *
     * @param kbId      知识库主键
     * @param query     查询文本
     * @param topK      召回条数，为空取知识库配置
     * @param threshold 相似度阈值，为空取知识库配置
     * @param rerank    是否开启重排
     * @return 命中切片（重排成功时按重排分降序，否则按相似度降序）
     */
    List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold, boolean rerank);

    /**
     * 检索测试：按查询文本召回切片，可选开启重排（Rerank）并指定**融合模式**。
     *
     * <p>{@code rerank=true} 时先按 {@code max(topK, rerankTopN)} 稠密召回，再调重排模型，
     * 并按 {@code rerankMode}（{@code replace} / {@code rank_rrf} / {@code score_fusion}）融合后截取 topK；
     * 重排失败（未配密钥 / 非 2xx / 超时 / 结构异常）时保持原顺序与原文分数，不阻断检索。</p>
     *
     * @param kbId       知识库主键
     * @param query      查询文本
     * @param topK       召回条数，为空取知识库配置
     * @param threshold  相似度阈值，为空取知识库配置
     * @param rerank     是否开启重排
     * @param rerankMode 融合模式，为空时取平台配置（{@code bizbuddy.ai.rag.rerank-mode}）
     * @return 命中切片
     */
    List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold, boolean rerank,
                                    String rerankMode);

    /**
     * 清理向量库中的切片向量（文档重新解析或删除时调用）。
     *
     * @param kbId      知识库主键
     * @param vectorIds 向量主键集合
     * @return 实际删除条数
     */
    int removeVectors(Long kbId, Collection<String> vectorIds);

}