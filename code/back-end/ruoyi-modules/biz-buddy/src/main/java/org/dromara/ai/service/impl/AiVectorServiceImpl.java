package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiEmbeddingClient;
import org.dromara.ai.core.AiRankFusion;
import org.dromara.ai.core.AiRerankClient;
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
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
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

    /**
     * 用量统计：入库消耗的 Token / 费用计入 {@code ai_usage_stat}
     */
    private final IAiUsageStatService usageStatService;

    private final AiVectorStore vectorStore;

    /**
     * 检索重排客户端（默认关闭；未配密钥 / 异常时降级为原顺序）
     */
    private final AiRerankClient rerankClient;

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
        AiDocument document = requireVectorizable(docId);
        if (STATUS_RUNNING.equals(document.getVectorStatus())) {
            throw new ServiceException("该文档正在向量化中，请稍后再试");
        }
        return doVectorize(document);
    }

    @Override
    public AiDocumentVo vectorizeClaimed(Long docId) {
        try {
            return doVectorize(requireVectorizable(docId));
        } catch (Exception e) {
            // 状态已由调用方占位：任何前置校验失败都必须落为「失败」，否则会一直显示「向量化中」
            markStatus(docId, STATUS_FAILED, abbreviate(e.getMessage()));
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("文档向量化失败：" + e.getMessage());
        }
    }

    /**
     * 校验文档可向量化（存在且已解析完成）。
     *
     * @param docId 文档主键
     * @return 文档实体
     */
    private AiDocument requireVectorizable(Long docId) {
        AiDocument document = documentMapper.selectById(docId);
        if (document == null) {
            throw new ServiceException("文档不存在或已被删除");
        }
        if (!PARSE_DONE.equals(document.getParseStatus())) {
            throw new ServiceException("文档尚未解析完成，请先执行「解析」");
        }
        return document;
    }

    /**
     * 执行向量化（调用方已完成守卫与状态占位）。
     *
     * @param document 文档实体
     * @return 向量化后的文档视图对象
     */
    private AiDocumentVo doVectorize(AiDocument document) {
        Long docId = document.getId();
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
        // 入库成本归属：页面触发取当前登录人；批量线程没有登录上下文时退回文档创建人
        Long usageUserId = resolveUsageUserId(document);
        try {
            // 重建语义：按文档清理**全部**历史向量（含切片已重建、业务记录不再指向的孤儿）
            vectorStore.deleteByDocId(knowledgeBase.getCollectionName(), knowledgeBase.getDimension(), docId);

            int pageCount = (chunks.size() + WRITE_PAGE_SIZE - 1) / WRITE_PAGE_SIZE;
            for (int page = 0; page < pageCount; page++) {
                int from = page * WRITE_PAGE_SIZE;
                int to = Math.min(from + WRITE_PAGE_SIZE, chunks.size());
                List<AiChunk> pageChunks = chunks.subList(from, to);
                // 安全合规（KB_INGEST）：送 embedding 的文本过脱敏（不改 ai_document_chunk 原始文本）
                List<String> texts = pageChunks.stream()
                    .map(chunk -> safetyGuard.mask(chunk.getContent(), SafetyScene.KB_INGEST))
                    .toList();
                AiEmbeddingClient.EmbeddingResult embedded = embeddingClient.embed(model, apiKey, texts);
                // 先记账再落库：即使后续写库失败，已消耗的 Token 也不会从成本视图消失
                recordIngestUsage(usageUserId, model.getId(), embedded.totalTokens());
                List<double[]> vectors = embedded.vectors();
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

    /**
     * 记录一批入库（向量化）消耗的 Token 与费用。
     *
     * <p>入库没有智能体归属，由用量服务统一记在「知识库入库」名下；统计失败只告警，
     * 不影响入库主流程。</p>
     *
     * @param userId 归属用户（为空时无法归属，跳过）
     * @param modelId 向量模型主键
     * @param tokens  本批消耗的 Token
     */
    private void recordIngestUsage(Long userId, Long modelId, long tokens) {
        if (userId == null || tokens <= 0) {
            return;
        }
        try {
            usageStatService.accumulateEmbedding(userId, modelId, tokens);
        } catch (Exception e) {
            log.warn("入库用量统计失败：userId={}, modelId={}, cause={}", userId, modelId, e.getMessage());
        }
    }

    /**
     * 解析入库用量的归属用户：优先当前登录人，异步线程无上下文时退回文档创建人。
     *
     * @param document 文档实体
     * @return 用户主键；均缺失时返回 null
     */
    private Long resolveUsageUserId(AiDocument document) {
        try {
            Long current = LoginHelper.getUserId();
            if (current != null) {
                return current;
            }
        } catch (Exception ignored) {
            // 批量任务线程没有 Sa-Token 上下文，继续走文档创建人
        }
        return document.getCreateBy();
    }

    @Override
    public List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold) {
        // 默认检索：是否重排**按知识库配置**（不在调用方写死）
        return retrieve(kbId, query, topK, threshold, null, null);
    }

    @Override
    public List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold, boolean rerank) {
        return retrieve(kbId, query, topK, threshold, Boolean.valueOf(rerank), null);
    }

    /**
     * 检索（含可选重排）。
     *
     * @param kbId       知识库主键
     * @param query      查询文本
     * @param topK       返回条数（为空取知识库配置）
     * @param threshold  相似度阈值（为空取知识库配置）
     * @param rerank     重排开关**覆盖**：{@code null}＝按知识库配置（{@code rerank_enabled}）；
     *                   {@code true}/{@code false}＝显式要求/拒绝本次重排（供「检索测试」做 A/B）
     * @param rerankMode 融合模式显式覆盖（为空取平台配置）
     * @return 命中列表
     */
    @Override
    public List<AiRetrievalHitVo> retrieve(Long kbId, String query, Integer topK, BigDecimal threshold, Boolean rerank,
                                           String rerankMode) {
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

        // 本次是否重排：请求级参数可覆盖，否则按知识库配置；且必须该知识库的重排模型可用
        boolean wantRerank = rerank != null
            ? rerank : "1".equals(StringUtils.trim(knowledgeBase.getRerankEnabled()));
        AiModelConfig rerankModel = wantRerank ? resolveRerankModel(knowledgeBase) : null;

        // 开启重排时先多召回（max(topK, rerankTopN)），给重排模型更大的候选池
        int recallLimit = rerankModel != null ? Math.max(limit, aiProperties.getRag().getRerankTopN()) : limit;

        double[] queryVector = embeddingClient.embedOne(model, apiKey, query);
        List<AiVectorStore.VectorHit> hits = vectorStore.search(
            knowledgeBase.getCollectionName(), knowledgeBase.getDimension(), queryVector, recallLimit, scoreThreshold);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<AiRetrievalHitVo> result = buildHits(hits);
        if (rerankModel == null) {
            return result;
        }
        return applyRerank(rerankModel, query, result, limit, resolveRerankMode(rerankMode));
    }

    /**
     * 解析本次生效的融合模式：显式参数优先，其次平台配置，无法识别时回退 {@code replace}。
     *
     * @param rerankMode 显式融合模式（可为空）
     * @return 融合模式
     */
    private AiRankFusion.Mode resolveRerankMode(String rerankMode) {
        String mode = StringUtils.isNotBlank(rerankMode) ? rerankMode : aiProperties.getRag().getRerankMode();
        return AiRankFusion.Mode.from(mode);
    }

    /**
     * 对稠密召回结果做文本重排，并按配置的融合模式（{@code replace} / {@code rank_rrf} / {@code score_fusion}）
     * 融合后截取 topK。
     *
     * <p><b>score 口径</b>：重排成功时命中项 {@code score} 替换为<b>融合分</b>（量纲随模式：{@code replace}=重排分 /
     * {@code rank_rrf}=RRF 分 / {@code score_fusion}=归一化加权分，均不可与「未重排」的余弦分直接比较）；
     * 重排降级时<b>保留原余弦分与原始顺序</b>。</p>
     *
     * <p><b>条件触发</b>：当 {@code rerankTriggerMargin > 0} 且「首条与次条向量分差 ≥ margin」时，
     * 认为向量头部已足够确信，<b>跳过重排调用</b>直接返回原序 topK，节省一次模型调用。</p>
     *
     * @param rerankModel 该知识库绑定的重排模型（连接信息取自模型配置）
     * @param query  查询文本
     * @param hits   稠密召回结果（已按相似度降序）
     * @param topK   最终返回条数
     * @param mode   融合模式
     * @return 重排融合后（或降级原序）截取 topK 的结果
     */
    private List<AiRetrievalHitVo> applyRerank(AiModelConfig rerankModel, String query, List<AiRetrievalHitVo> hits,
                                               int topK, AiRankFusion.Mode mode) {
        if (shouldSkipRerank(hits)) {
            return truncate(hits, topK);
        }
        List<String> documents = hits.stream().map(AiRetrievalHitVo::getContent).toList();
        AiRerankClient.RerankOutcome outcome = rerankClient.rerank(
            modelConfigService.decryptApiKey(rerankModel.getId()), rerankModel.getBaseUrl(),
            rerankModel.getModelCode(), query, documents, documents.size());
        if (outcome.degraded() || outcome.docs().isEmpty()) {
            // 降级：保持原余弦分与原顺序，仅按 topK 截断
            log.warn("检索重排降级为原顺序（kb 检索不受影响），候选数={}", hits.size());
            return truncate(hits, topK);
        }
        List<Double> vectorScores = hits.stream()
            .map(hit -> hit.getScore() == null ? 0.0 : hit.getScore())
            .toList();
        AiProperties.Rag rag = aiProperties.getRag();
        List<AiRankFusion.Fused> fused = AiRankFusion.fuse(
            vectorScores, outcome.docs(), mode, rag.getRerankAlpha(), rag.getRerankRrfK());
        List<AiRetrievalHitVo> reordered = new ArrayList<>(fused.size());
        for (AiRankFusion.Fused item : fused) {
            int index = item.index();
            if (index < 0 || index >= hits.size()) {
                continue;
            }
            AiRetrievalHitVo hit = hits.get(index);
            hit.setScore(item.score());
            reordered.add(hit);
            if (reordered.size() >= topK) {
                break;
            }
        }
        return reordered;
    }

    /**
     * 判断本次是否可以跳过重排（条件触发）。
     *
     * <p>仅当配置了正阈值、候选 ≥ 2、且首条与次条的余弦分差 ≥ 阈值时跳过；否则照常重排。</p>
     *
     * @param hits 稠密召回结果（已按相似度降序）
     * @return 是否跳过重排
     */
    private boolean shouldSkipRerank(List<AiRetrievalHitVo> hits) {
        double margin = aiProperties.getRag().getRerankTriggerMargin();
        if (margin <= 0 || hits.size() < 2) {
            return false;
        }
        Double first = hits.get(0).getScore();
        Double second = hits.get(1).getScore();
        if (first == null || second == null) {
            return false;
        }
        return first - second >= margin;
    }

    /**
     * 按 topK 截断（不超限时原样返回）。
     *
     * @param hits 命中列表
     * @param topK 目标条数
     * @return 截断结果
     */
    private List<AiRetrievalHitVo> truncate(List<AiRetrievalHitVo> hits, int topK) {
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
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
            vo.setHeadingPath(chunk.getHeadingPath());
            vo.setPageNo(chunk.getPageNo());
            vo.setCharStart(chunk.getCharStart());
            vo.setCharEnd(chunk.getCharEnd());
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
     * 解析该知识库绑定的重排模型（连接信息取自「模型配置」，{@code model_type=rerank}）。
     *
     * <p><b>不抛异常</b>：重排是增强通道——未绑定 / 不存在 / 类型不符 / 已停用都只表示「本次不重排」，
     * 绝不阻断检索（与 {@code docs/22} 对「决策模型是增强通道」的口径一致）。</p>
     *
     * @param knowledgeBase 知识库实体
     * @return 可用的重排模型；不可用时返回 {@code null}
     */
    private AiModelConfig resolveRerankModel(AiKnowledgeBase knowledgeBase) {
        Long modelId = knowledgeBase.getRerankModelId();
        if (modelId == null) {
            log.debug("知识库 {} 启用了重排但未绑定重排模型，本次不重排", knowledgeBase.getId());
            return null;
        }
        AiModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null
            || !AiModelConfig.TYPE_RERANK.equalsIgnoreCase(StringUtils.trim(model.getModelType()))
            || !"0".equals(StringUtils.trim(model.getStatus()))) {
            log.warn("知识库 {} 的重排模型不可用（不存在 / 非 rerank 类型 / 已停用），本次不重排",
                knowledgeBase.getId());
            return null;
        }
        return model;
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