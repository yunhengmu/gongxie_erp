package org.dromara.ai.service.impl;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiEmbeddingClient;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    private AiSafetyGuard safetyGuard;

    @Mock
    private IAiSafetyEventService safetyEventService;

    private final AiProperties aiProperties = new AiProperties();

    private AiVectorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiVectorServiceImpl(chunkMapper, documentMapper, modelConfigMapper,
            knowledgeBaseService, modelConfigService, embeddingClient, vectorStore, aiProperties,
            safetyGuard, safetyEventService);
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