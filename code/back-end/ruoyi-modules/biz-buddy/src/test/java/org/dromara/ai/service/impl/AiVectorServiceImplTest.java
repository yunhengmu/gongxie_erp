package org.dromara.ai.service.impl;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiEmbeddingClient;
import org.dromara.ai.core.AiRerankClient;
import org.dromara.ai.core.AiVectorStore;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
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
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link AiVectorServiceImpl} 单元测试：向量化前置校验、分批写入与维度校验、检索命中回填与向量清理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI 向量化与检索 Service")
class AiVectorServiceImplTest {

    @Mock
    private AiChunkMapper chunkMapper;

    @Mock
    private AiDocumentMapper documentMapper;

    @Mock
    private AiModelConfigMapper modelConfigMapper;

    @Mock
    private IAiKnowledgeBaseService knowledgeBaseService;

    @Mock
    private IAiModelConfigService modelConfigService;

    @Mock
    private AiEmbeddingClient embeddingClient;

    @Mock
    private AiVectorStore vectorStore;

    @Mock
    private AiRerankClient rerankClient;

    @Mock
    private AiSafetyGuard safetyGuard;

    @Mock
    private IAiSafetyEventService safetyEventService;

    private final AiProperties aiProperties = new AiProperties();

    /**
     * 重排模型自身的密钥 / 端点 / 模型标识（本次改造后重排不再复用向量模型密钥）。
     */
    private static final String RERANK_KEY = "rerank-key";

    private static final String RERANK_BASE =
        "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private static final String RERANK_CODE = "gte-rerank-v2";

    private AiVectorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiVectorServiceImpl(chunkMapper, documentMapper, modelConfigMapper,
            knowledgeBaseService, modelConfigService, embeddingClient, vectorStore, rerankClient, aiProperties,
            safetyGuard, safetyEventService);
        // 融合相关配置复位到与「纯替换」等价，避免用例间相互影响；召回条数固定 20 以匹配桩
        aiProperties.getRag().setRerankMode("replace");
        aiProperties.getRag().setRerankTriggerMargin(0);
        aiProperties.getRag().setRerankTopN(20);
        // 安全守卫默认「原样放行 / 原样脱敏」，普通文本入参不变，既有断言不受影响
        lenient().when(safetyGuard.guard(any(), any()))
            .thenAnswer(invocation -> SafetyDecision.pass(invocation.getArgument(0)));
        lenient().when(safetyGuard.mask(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AiKnowledgeBase kb(Long id, Integer dimension, Long modelId) {
        AiKnowledgeBase kb = new AiKnowledgeBase();
        kb.setId(id);
        kb.setCollectionName("col");
        kb.setDimension(dimension);
        kb.setEmbeddingModelId(modelId);
        return kb;
    }

    private AiModelConfig model(Long id) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setModelCode("embed");
        return model;
    }

    /**
     * 知识库桩：启用重排并绑定指定重排模型。
     */
    private AiKnowledgeBase kbWithRerank(Long id, Integer dimension, Long embeddingModelId, Long rerankModelId) {
        AiKnowledgeBase kb = kb(id, dimension, embeddingModelId);
        kb.setRerankEnabled("1");
        kb.setRerankModelId(rerankModelId);
        return kb;
    }

    /**
     * 重排模型桩：类型 rerank、状态正常，带端点与模型标识。
     */
    private AiModelConfig rerankModel(Long id) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setModelType(AiModelConfig.TYPE_RERANK);
        model.setStatus("0");
        model.setModelCode(RERANK_CODE);
        model.setBaseUrl(RERANK_BASE);
        return model;
    }

    private AiDocument parsedDocument() {
        AiDocument doc = new AiDocument();
        doc.setId(1L);
        doc.setKbId(2L);
        doc.setDocName("doc");
        doc.setParseStatus("2");
        doc.setVectorStatus("0");
        return doc;
    }

    /**
     * 准备向量化的公共桩。
     */
    private void stubVectorizeCommon(AiKnowledgeBase kb, AiModelConfig model) {
        when(documentMapper.selectById(1L)).thenReturn(parsedDocument());
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(modelConfigMapper.selectById(kb.getEmbeddingModelId())).thenReturn(model);
        when(modelConfigService.decryptApiKey(model.getId())).thenReturn("key");
    }

    @Test
    @DisplayName("向量化：文档不存在抛异常")
    void shouldRejectVectorizeWhenDocAbsent() {
        when(documentMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("向量化：未解析完成抛异常")
    void shouldRejectVectorizeWhenNotParsed() {
        AiDocument doc = parsedDocument();
        doc.setParseStatus("0");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("向量化：正在向量化抛异常")
    void shouldRejectVectorizeWhenRunning() {
        AiDocument doc = parsedDocument();
        doc.setVectorStatus("1");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("向量化入口：已占位的文档跳过「向量化中」守卫并完成写入")
    void shouldVectorizeClaimedDocument() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        stubVectorizeCommon(kb, model);
        AiDocument claimed = parsedDocument();
        claimed.setVectorStatus("1");
        when(documentMapper.selectById(1L)).thenReturn(claimed);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1L, "a")));
        when(embeddingClient.embed(model, "key", List.of("a"))).thenReturn(List.of(new double[]{1, 2, 3}));
        when(vectorStore.add(eq("col"), eq(3), any())).thenReturn(List.of("v1"));
        when(documentMapper.selectVoById(1L)).thenReturn(new AiDocumentVo());

        assertNotNull(service.vectorizeClaimed(1L));
    }

    @Test
    @DisplayName("向量化入口：已占位但前置校验失败时落为「失败」，不卡在「向量化中」")
    void shouldMarkFailedWhenClaimedVectorizeRejected() {
        AiDocument doc = parsedDocument();
        doc.setVectorStatus("1");
        doc.setParseStatus("0");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        assertThrows(ServiceException.class, () -> service.vectorizeClaimed(1L));

        ArgumentCaptor<AiDocument> captor = ArgumentCaptor.forClass(AiDocument.class);
        verify(documentMapper).updateById(captor.capture());
        assertEquals("3", captor.getValue().getVectorStatus());
    }

    @Test
    @DisplayName("向量化：向量模型缺失抛异常")
    void shouldRejectVectorizeWhenModelAbsent() {
        when(documentMapper.selectById(1L)).thenReturn(parsedDocument());
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb(2L, 3, 7L));
        when(modelConfigMapper.selectById(7L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("向量化：切片为空抛异常")
    void shouldRejectVectorizeWhenNoChunks() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        stubVectorizeCommon(kb, model);
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("向量化：写入向量并回写切片向量主键")
    void shouldVectorizeDocument() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        stubVectorizeCommon(kb, model);
        AiChunk c1 = chunk(1L, "a");
        AiChunk c2 = chunk(2L, "b");
        when(chunkMapper.selectList(any())).thenReturn(List.of(c1, c2));
        when(embeddingClient.embed(model, "key", List.of("a", "b")))
            .thenReturn(List.of(new double[]{1, 2, 3}, new double[]{4, 5, 6}));
        when(vectorStore.add(eq("col"), eq(3), any())).thenReturn(List.of("v1", "v2"));
        when(documentMapper.selectVoById(1L)).thenReturn(new AiDocumentVo());

        AiDocumentVo result = service.vectorizeDocument(1L);

        assertNotNull(result);
        verify(chunkMapper).updateBatchById(any(), eq(50));
        verify(documentMapper, atLeastOnce()).updateById(any(AiDocument.class));
    }

    @Test
    @DisplayName("向量化：向量维度不一致抛异常")
    void shouldRejectVectorizeOnDimensionMismatch() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        stubVectorizeCommon(kb, model);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1L, "a")));
        when(embeddingClient.embed(model, "key", List.of("a")))
            .thenReturn(List.of(new double[]{1, 2}));

        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("向量化：嵌入调用异常包装为业务异常")
    void shouldWrapVectorizeError() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        stubVectorizeCommon(kb, model);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1L, "a")));
        when(embeddingClient.embed(model, "key", List.of("a"))).thenThrow(new RuntimeException("boom"));

        assertThrows(ServiceException.class, () -> service.vectorizeDocument(1L));
    }

    @Test
    @DisplayName("检索：查询内容为空抛异常")
    void shouldRejectBlankRetrieveQuery() {
        assertThrows(ServiceException.class, () -> service.retrieve(2L, "  ", null, null));
    }

    @Test
    @DisplayName("检索：无命中返回空列表")
    void shouldReturnEmptyOnNoHits() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(modelConfigMapper.selectById(7L)).thenReturn(model);
        when(modelConfigService.decryptApiKey(7L)).thenReturn("key");
        when(embeddingClient.embedOne(model, "key", "q")).thenReturn(new double[]{1, 2, 3});
        when(vectorStore.search(eq("col"), eq(3), any(double[].class), eq(5), eq(0.3))).thenReturn(List.of());

        assertTrue(service.retrieve(2L, "q", null, null).isEmpty());
    }

    @Test
    @DisplayName("检索：命中回填切片与文档信息，非法主键跳过")
    void shouldBuildRetrievalHits() {
        AiKnowledgeBase kb = kb(2L, 3, 7L);
        AiModelConfig model = model(7L);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(modelConfigMapper.selectById(7L)).thenReturn(model);
        when(modelConfigService.decryptApiKey(7L)).thenReturn("key");
        when(embeddingClient.embedOne(model, "key", "q")).thenReturn(new double[]{1, 2, 3});
        when(vectorStore.search(eq("col"), eq(3), any(double[].class), eq(3), eq(0.5)))
            .thenReturn(List.of(
                new AiVectorStore.VectorHit("1", "10", 0, 0.9),
                new AiVectorStore.VectorHit("x", "10", 0, 0.8),
                new AiVectorStore.VectorHit("2", "10", 1, 0.7)));
        AiChunk chunk = chunk(1L, "命中内容");
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk));
        AiDocument doc = new AiDocument();
        doc.setId(10L);
        doc.setDocName("来源文档");
        when(documentMapper.selectByIds(any())).thenReturn(List.of(doc));

        List<AiRetrievalHitVo> result = service.retrieve(2L, "q", 3, new java.math.BigDecimal("0.5"));

        assertEquals(1, result.size());
        assertEquals("命中内容", result.get(0).getContent());
        assertEquals("来源文档", result.get(0).getDocName());
        assertEquals(0.9, result.get(0).getScore().doubleValue());
    }

    @Test
    @DisplayName("检索：开启重排时多召回、按重排分重排并截取 topK")
    void shouldRerankRetrieval() {
        AiKnowledgeBase kb = kbWithRerank(2L, 3, 7L, 8L);
        AiModelConfig model = model(7L);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(modelConfigMapper.selectById(7L)).thenReturn(model);
        when(modelConfigMapper.selectById(8L)).thenReturn(rerankModel(8L));
        when(modelConfigService.decryptApiKey(7L)).thenReturn("key");
        when(modelConfigService.decryptApiKey(8L)).thenReturn(RERANK_KEY);
        when(embeddingClient.embedOne(model, "key", "q")).thenReturn(new double[]{1, 2, 3});
        // topK=2、rerankTopN=20 → 召回上限取 20
        when(vectorStore.search(eq("col"), eq(3), any(double[].class), eq(20), eq(0.3)))
            .thenReturn(List.of(
                new AiVectorStore.VectorHit("1", "10", 0, 0.9),
                new AiVectorStore.VectorHit("2", "10", 1, 0.8)));
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk(1L, "A"), chunk(2L, "B")));
        AiDocument doc = new AiDocument();
        doc.setId(10L);
        doc.setDocName("来源文档");
        when(documentMapper.selectByIds(any())).thenReturn(List.of(doc));
        // 重排把下标 1（B）提到首位，分 0.95；下标 0（A）次之，分 0.3
        when(rerankClient.rerank(eq(RERANK_KEY), eq(RERANK_BASE), eq(RERANK_CODE), eq("q"), any(), eq(2)))
            .thenReturn(new AiRerankClient.RerankOutcome(false, List.of(
                new AiRerankClient.RankedDoc(1, 0.95),
                new AiRerankClient.RankedDoc(0, 0.3))));

        List<AiRetrievalHitVo> result = service.retrieve(2L, "q", 2, null, true);

        assertEquals(2, result.size());
        assertEquals("B", result.get(0).getContent());
        assertEquals(0.95, result.get(0).getScore().doubleValue());
        assertEquals("A", result.get(1).getContent());
        assertEquals(0.3, result.get(1).getScore().doubleValue());
    }

    @Test
    @DisplayName("检索：重排降级时保持原顺序与原余弦分并截取 topK")
    void shouldKeepOriginalOrderWhenRerankDegraded() {
        AiKnowledgeBase kb = kbWithRerank(2L, 3, 7L, 8L);
        AiModelConfig model = model(7L);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(modelConfigMapper.selectById(7L)).thenReturn(model);
        when(modelConfigMapper.selectById(8L)).thenReturn(rerankModel(8L));
        when(modelConfigService.decryptApiKey(7L)).thenReturn("key");
        when(modelConfigService.decryptApiKey(8L)).thenReturn(RERANK_KEY);
        when(embeddingClient.embedOne(model, "key", "q")).thenReturn(new double[]{1, 2, 3});
        when(vectorStore.search(eq("col"), eq(3), any(double[].class), eq(20), eq(0.3)))
            .thenReturn(List.of(
                new AiVectorStore.VectorHit("1", "10", 0, 0.9),
                new AiVectorStore.VectorHit("2", "10", 1, 0.8)));
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk(1L, "A"), chunk(2L, "B")));
        AiDocument doc = new AiDocument();
        doc.setId(10L);
        doc.setDocName("来源文档");
        when(documentMapper.selectByIds(any())).thenReturn(List.of(doc));
        when(rerankClient.rerank(any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(AiRerankClient.RerankOutcome.degradedOrder(2));

        List<AiRetrievalHitVo> result = service.retrieve(2L, "q", 1, null, true);

        assertEquals(1, result.size());
        assertEquals("A", result.get(0).getContent());
        assertEquals(0.9, result.get(0).getScore().doubleValue());
    }

    /**
     * 检索公共桩：知识库/模型/密钥/查询向量 + 三命中召回。
     */
    private void stubRetrieveCommon(List<AiVectorStore.VectorHit> vectorHits) {
        AiKnowledgeBase kb = kbWithRerank(2L, 3, 7L, 8L);
        AiModelConfig model = model(7L);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(modelConfigMapper.selectById(7L)).thenReturn(model);
        when(modelConfigMapper.selectById(8L)).thenReturn(rerankModel(8L));
        when(modelConfigService.decryptApiKey(7L)).thenReturn("key");
        when(modelConfigService.decryptApiKey(8L)).thenReturn(RERANK_KEY);
        when(embeddingClient.embedOne(model, "key", "q")).thenReturn(new double[]{1, 2, 3});
        when(vectorStore.search(eq("col"), eq(3), any(double[].class), eq(20), eq(0.3))).thenReturn(vectorHits);
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk(1L, "C1"), chunk(2L, "C2"), chunk(3L, "C3")));
        AiDocument doc = new AiDocument();
        doc.setId(10L);
        doc.setDocName("来源文档");
        when(documentMapper.selectByIds(any())).thenReturn(List.of(doc));
    }

    @Test
    @DisplayName("检索：显式 rerankMode=score_fusion 时按归一化加权融合（保护向量头部）")
    void shouldFuseByScoreWhenModeExplicit() {
        stubRetrieveCommon(List.of(
            new AiVectorStore.VectorHit("1", "10", 0, 0.95),
            new AiVectorStore.VectorHit("2", "10", 1, 0.7),
            new AiVectorStore.VectorHit("3", "10", 2, 0.5)));
        when(rerankClient.rerank(eq(RERANK_KEY), eq(RERANK_BASE), eq(RERANK_CODE), eq("q"), any(), eq(3)))
            .thenReturn(new AiRerankClient.RerankOutcome(false, List.of(
                new AiRerankClient.RankedDoc(1, 0.95),
                new AiRerankClient.RankedDoc(0, 0.9),
                new AiRerankClient.RankedDoc(2, 0.5))));

        List<AiRetrievalHitVo> result = service.retrieve(2L, "q", 3, null, true, "score_fusion");

        // 向量头部 C1 归一化分最高，叠加后仍居首（纯替换会给出 C2 居首）
        assertEquals("C1", result.get(0).getContent());
        assertEquals("C2", result.get(1).getContent());
        assertEquals("C3", result.get(2).getContent());
    }

    @Test
    @DisplayName("检索：配置 rerankMode=rank_rrf 时按排名融合（向量头部与重排头部都在前列）")
    void shouldFuseByRankWhenConfigured() {
        aiProperties.getRag().setRerankMode("rank_rrf");
        stubRetrieveCommon(List.of(
            new AiVectorStore.VectorHit("1", "10", 0, 0.95),
            new AiVectorStore.VectorHit("2", "10", 1, 0.7),
            new AiVectorStore.VectorHit("3", "10", 2, 0.5)));
        when(rerankClient.rerank(eq(RERANK_KEY), eq(RERANK_BASE), eq(RERANK_CODE), eq("q"), any(), eq(3)))
            .thenReturn(new AiRerankClient.RerankOutcome(false, List.of(
                new AiRerankClient.RankedDoc(1, 0.9),
                new AiRerankClient.RankedDoc(2, 0.6),
                new AiRerankClient.RankedDoc(0, 0.1))));

        List<AiRetrievalHitVo> result = service.retrieve(2L, "q", 3, null, true);

        assertEquals("C2", result.get(0).getContent());
        assertEquals("C1", result.get(1).getContent());
        assertEquals("C3", result.get(2).getContent());
    }

    @Test
    @DisplayName("检索：条件触发——首条与次条向量分差达阈值时跳过重排调用")
    void shouldSkipRerankWhenTopGapExceedsMargin() {
        aiProperties.getRag().setRerankTriggerMargin(0.05);
        stubRetrieveCommon(List.of(
            new AiVectorStore.VectorHit("1", "10", 0, 0.9),
            new AiVectorStore.VectorHit("2", "10", 1, 0.8)));

        List<AiRetrievalHitVo> result = service.retrieve(2L, "q", 1, null, true);

        verify(rerankClient, never()).rerank(any(), any(), any(), any(), any(), anyInt());
        assertEquals(1, result.size());
        assertEquals("C1", result.get(0).getContent());
        assertEquals(0.9, result.get(0).getScore().doubleValue());
    }

    @Test
    @DisplayName("移除向量：空集合返回 0，正常调用向量库")
    void shouldRemoveVectors() {
        assertEquals(0, service.removeVectors(2L, List.of()));

        AiKnowledgeBase kb = kb(2L, 3, 7L);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb);
        when(vectorStore.delete("col", 3, List.of("v1"))).thenReturn(1);

        assertEquals(1, service.removeVectors(2L, List.of("v1")));
    }

    private AiChunk chunk(Long id, String content) {
        AiChunk chunk = new AiChunk();
        chunk.setId(id);
        chunk.setDocId(10L);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        return chunk;
    }

}