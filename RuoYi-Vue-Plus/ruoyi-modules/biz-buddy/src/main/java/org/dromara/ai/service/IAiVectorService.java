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
     * 清理向量库中的切片向量（文档重新解析或删除时调用）。
     *
     * @param kbId      知识库主键
     * @param vectorIds 向量主键集合
     * @return 实际删除条数
     */
    int removeVectors(Long kbId, Collection<String> vectorIds);

}