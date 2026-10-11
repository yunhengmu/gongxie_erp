package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiEmbeddingClient;
import org.dromara.ai.core.AiVectorStore;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.AiChunk;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.dromara.ai.mapper.AiChunkMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 知识库向量化与检索 Service 业务层处理
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiVectorServiceImpl implements IAiVectorService {

    /**
     * 向量化状态：向量化中
     */
    private static final String STATUS_RUNNING = "1";

    /**
     * 向量化状态：已向量化
     */
    private static final String STATUS_DONE = "2";

    /**
     * 向量化状态：向量化失败
     */
    private static final String STATUS_FAILED = "3";

    /**
     * 解析状态：已解析
     */
    private static final String PARSE_DONE = "2";

    /**
     * 每批落库的切片数量（控制单批向量计算与写库的内存占用）
     */
    private static final int WRITE_PAGE_SIZE = 50;

    private final AiChunkMapper chunkMapper;

    private final AiDocumentMapper documentMapper;

    private final AiModelConfigMapper modelConfigMapper;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    private final IAiModelConfigService modelConfigService;

    private final AiEmbeddingClient embeddingClient;

    private final AiVectorStore vectorStore;

    private final AiProperties aiProperties;

    /**
     * 安全合规守卫（入库审核 + PII 脱敏）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * 安全事件留痕
     */
    private final IAiSafetyEventService safetyEventService;

    @Override
    public AiDocumentVo vectorizeDocument(Long docId) {
        AiDocument document = documentMapper.selectById(docId);
        if (document == null) {
            throw new ServiceException("文档不存在或已被删除");
        }
        if (!PARSE_DONE.equals(document.getParseStatus())) {
            throw new ServiceException("文档尚未解析完成，请先执行「解析」");
        }
        if (STATUS_RUNNING.equals(document.getVectorStatus())) {
            throw new ServiceException("该文档正在向量化中，请稍后再试");
        }
        AiKnowledgeBase knowledgeBase = knowledgeBaseService.getEnabledKnowledgeBase(document.getKbId());
        AiModelConfig model = resolveEmbeddingModel(knowledgeBase.getEmbeddingModelId());
        String apiKey = modelConfigService.decryptApiKey(model.getId());

        List<AiChunk> chunks = listChunks(docId);
        if (chunks.isEmpty()) {
            throw new ServiceException("文档切片为空，请先重新解析文档");
        }
        // 安全合规（KB_INGEST）：入库前审核全部切片文本，命中阻断则拒绝入库（异常路径会把状态置 FAILED）
        auditChunks(docId, chunks);

        markStatus(docId, STATUS_RUNNING, "");
        try {
            // 重建语义：先清理该文档在本知识库中的旧向量，避免残留脏数据
            removeVectors(knowledgeBase.getId(), collectVectorIds(chunks));

            int pageCount = (chunks.size() + WRITE_PAGE_SIZE - 1) / WRITE_PAGE_SIZE;
            for (int page = 0; page < pageCount; page++) {
                int from = page * WRITE_PAGE_SIZE;
                int to = Math.min(from + WRITE_PAGE_SIZE, chunks.size());
                List<AiChunk> pageChunks = chunks.subList(from, to);
                // 安全合规（KB_INGEST）：送 embedding 的文本过脱敏（不改 ai_document_chunk 原始文本）
                List<String> texts = pageChunks.stream()
                    .map(chunk -> safetyGuard.mask(chunk.getContent(), SafetyScene.KB_INGEST))
                    .toList();
                List<double[]> vectors = embeddingClient.embed(model, apiKey, texts);
                validateDimension(vectors, knowledgeBase);
                writePage(knowledgeBase, document, pageChunks, vectors);
            }

            AiDocument update = new AiDocument();
            update.setId(docId);
            update.setVectorStatus(STATUS_DONE);
            update.setVectorErrorMsg("");
            documentMapper.updateById(update);
            log.info("文档向量化完成：docId={}, chunks={}, collection={}", docId, chunks.size(), knowledgeBase.getCollectionName());
        } catch (Exception e) {
            markStatus(docId, STATUS_FAILED, abbreviate(e.getMessage()));
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("文档向量化失败：" + e.getMessage());
        }
        return documentMapper.selectVoById(docId);
    }

    @Override
    public List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold) {
        if (StringUtils.isBlank(query)) {
            throw new ServiceException("请输入查询内容");
        }
        AiKnowledgeBase knowledgeBase = knowledgeBaseService.getEnabledKnowledgeBase(kbId);
        AiModelConfig model = resolveEmbeddingModel(knowledgeBase.getEmbeddingModelId());
        String apiKey = modelConfigService.decryptApiKey(model.getId());

        int limit = topK != null ? topK : (knowledgeBase.getRetrievalTopK() != null ? knowledgeBase.getRetrievalTopK() : 5);
        double scoreThreshold = threshold != null
            ? threshold.doubleValue()
            : (knowledgeBase.getSimilarityThreshold() != null
                ? knowledgeBase.getSimilarityThreshold().doubleValue()
                : aiProperties.getRetrievalScoreThreshold());

        double[] queryVector = embeddingClient.embedOne(model, apiKey, query);
        List<AiVectorStore.VectorHit> hits = vectorStore.search(
            knowledgeBase.getCollectionName(), knowledgeBase.getDimension(), queryVector, limit, scoreThreshold);
        if (hits.isEmpty()) {
            return List.of();
        }
        return buildHits(hits);
    }

    @Override
    public int removeVectors(Long kbId, Collection<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return 0;
        }
        AiKnowledgeBase knowledgeBase = knowledgeBaseService.getEnabledKnowledgeBase(kbId);
        return vectorStore.delete(knowledgeBase.getCollectionName(), knowledgeBase.getDimension(), vectorIds);
    }

    /**
     * 写入一批切片向量，并回写切片的向量主键。
     *
     * @param knowledgeBase 知识库
     * @param document      文档
     * @param chunks        本批切片
     * @param vectors       本批向量
     */
    private void writePage(AiKnowledgeBase knowledgeBase, AiDocument document,
                           List<AiChunk> chunks, List<double[]> vectors) {
        List<AiVectorStore.VectorDoc> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            AiChunk chunk = chunks.get(i);
            docs.add(new AiVectorStore.VectorDoc(
                String.valueOf(chunk.getId()),
                String.valueOf(document.getId()),
                document.getDocName(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                vectors.get(i)));
        }
        List<String> vectorIds = vectorStore.add(
            knowledgeBase.getCollectionName(), knowledgeBase.getDimension(), docs);

        List<AiChunk> updates = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            AiChunk update = new AiChunk();
            update.setId(chunks.get(i).getId());
            update.setVectorId(vectorIds.get(i));
            updates.add(update);
        }
        chunkMapper.updateBatchById(updates, WRITE_PAGE_SIZE);
    }

    /**
     * 组装检索命中结果：以业务库切片与文档为准，保证内容与归属可追溯。
     *
     * @param hits 向量命中结果
     * @return 命中视图对象
     */
    private List<AiRetrievalHitVo> buildHits(List<AiVectorStore.VectorHit> hits) {
        Set<Long> chunkIds = new HashSet<>();
        for (AiVectorStore.VectorHit hit : hits) {
            Long chunkId = toLong(hit.chunkId());
            if (chunkId != null) {
                chunkIds.add(chunkId);
            }
        }
        Map<Long, AiChunk> chunkMap = new LinkedHashMap<>();
        if (!chunkIds.isEmpty()) {
            for (AiChunk chunk : chunkMapper.selectByIds(chunkIds)) {
                chunkMap.put(chunk.getId(), chunk);
            }
        }
        Set<Long> docIds = new HashSet<>();
        chunkMap.values().forEach(chunk -> docIds.add(chunk.getDocId()));
        Map<Long, String> docNames = new LinkedHashMap<>();
        if (!docIds.isEmpty()) {
            for (AiDocument document : documentMapper.selectByIds(docIds)) {
                docNames.put(document.getId(), document.getDocName());
            }
        }

        List<AiRetrievalHitVo> result = new ArrayList<>();
        for (AiVectorStore.VectorHit hit : hits) {
            Long chunkId = toLong(hit.chunkId());
            AiChunk chunk = chunkId == null ? null : chunkMap.get(chunkId);
            if (chunk == null) {
                // 切片已重建或删除，向量为历史残留，跳过（下次向量化会清理）
                continue;
            }
            AiRetrievalHitVo vo = new AiRetrievalHitVo();
            vo.setChunkIndex(chunk.getChunkIndex());
            vo.setContent(chunk.getContent());
            vo.setDocId(chunk.getDocId());
            vo.setDocName(docNames.get(chunk.getDocId()));
            vo.setScore(hit.score());
            result.add(vo);
        }
        return result;
    }

    /**
     * 校验向量维度与知识库一致。
     *
     * @param vectors       向量列表
     * @param knowledgeBase 知识库
     */
    private void validateDimension(List<double[]> vectors, AiKnowledgeBase knowledgeBase) {
        for (double[] vector : vectors) {
            if (vector.length != knowledgeBase.getDimension()) {
                throw new ServiceException("向量模型返回维度 " + vector.length
                    + "，与知识库配置的维度 " + knowledgeBase.getDimension() + " 不一致，请核对「模型配置」中的向量维度");
            }
        }
    }

    /**
     * 入库前安全审核：逐片过「知识库入库」守卫，命中即留痕，阻断则拒绝入库。
     *
     * <p>按片审核并合并命中，避免超长文档整体拼接占内存，且能定位命中片段。</p>
     *
     * @param docId  文档主键
     * @param chunks 全部切片
     */
    private void auditChunks(Long docId, List<AiChunk> chunks) {
        for (AiChunk chunk : chunks) {
            SafetyDecision decision = safetyGuard.guard(chunk.getContent(), SafetyScene.KB_INGEST);
            if (!decision.hit()) {
                continue;
            }
            safetyEventService.record(SafetyScene.KB_INGEST, decision, safetyGuard.engineName(),
                null, null, null, null, "docId=" + docId + "｜" + chunk.getContent());
            if (decision.blocked()) {
                throw new ServiceException("文档内容命中安全规则（命中："
                    + String.join("/", decision.hits()) + "），已拒绝入库");
            }
        }
    }

    /**
     * 查询文档切片。
     *
     * @param docId 文档主键
     * @return 切片列表
     */
    private List<AiChunk> listChunks(Long docId) {
        LambdaQueryWrapper<AiChunk> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChunk::getDocId, docId);
        lqw.orderByAsc(AiChunk::getChunkIndex);
        return chunkMapper.selectList(lqw);
    }

    /**
     * 收集切片上已记录的向量主键。
     *
     * @param chunks 切片列表
     * @return 向量主键集合
     */
    private List<String> collectVectorIds(List<AiChunk> chunks) {
        List<String> ids = new ArrayList<>();
        for (AiChunk chunk : chunks) {
            if (StringUtils.isNotBlank(chunk.getVectorId())) {
                ids.add(chunk.getVectorId());
            }
        }
        return ids;
    }

    /**
     * 解析知识库绑定的向量模型。
     *
     * @param modelId 模型主键
     * @return 模型配置
     */
    private AiModelConfig resolveEmbeddingModel(Long modelId) {
        AiModelConfig model = modelId == null ? null : modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw new ServiceException("知识库绑定的向量模型不存在或已被删除，请到「知识库管理」中重新选择");
        }
        return model;
    }

    /**
     * 更新文档向量化状态。
     *
     * @param docId    文档主键
     * @param status   状态
     * @param errorMsg 失败原因
     */
    private void markStatus(Long docId, String status, String errorMsg) {
        AiDocument update = new AiDocument();
        update.setId(docId);
        update.setVectorStatus(status);
        update.setVectorErrorMsg(errorMsg);
        documentMapper.updateById(update);
    }

    /**
     * 字符串转主键。
     *
     * @param value 字符串
     * @return 主键，非法时返回 null
     */
    private Long toLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 截断过长错误信息。
     *
     * @param message 原始信息
     * @return 截断结果
     */
    private String abbreviate(String message) {
        if (message == null) {
            return "向量化失败";
        }
        String trimmed = message.trim();
        return trimmed.length() > 900 ? trimmed.substring(0, 900) : trimmed;
    }

}