package org.dromara.ai.core;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.MilvusStore;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiVectorStore} 单元测试：封装逻辑与异常分支（底层 MilvusStore 全部打桩，不连 Milvus）。
 */
@DisplayName("Milvus 向量库门面")
class AiVectorStoreTest {

    @BeforeAll
    static void setUp() {
        AiTestSupport.initialize();
    }

    private AiProperties props() {
        return new AiProperties();
    }

    private AiVectorStore.VectorDoc doc(String chunkId, String docId, Integer index, String content) {
        return new AiVectorStore.VectorDoc(chunkId, docId, "n.md", index, content, new double[]{1.0, 2.0});
    }

    @Test
    @DisplayName("写入空切片返回空列表且不建立连接")
    void shouldReturnEmptyWhenNoDocs() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class)) {
            AiVectorStore store = new AiVectorStore(props());
            assertTrue(store.add("kb", 2, List.of()).isEmpty());
            assertTrue(construction.constructed().isEmpty());
            assertFalse(store.isReady("kb"));
        }
    }

    @Test
    @DisplayName("正常写入返回主键并建立集合连接")
    void shouldAddAndMarkReady() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.add(anyList())).thenReturn(Mono.empty()))) {
            AiVectorStore store = new AiVectorStore(props());
            List<String> ids = store.add("kb", 2, List.of(doc("c1", "d1", 0, "hello"), doc("c2", "d1", 1, "world")));
            assertEquals(2, ids.size());
            assertTrue(ids.stream().allMatch(id -> id != null && !id.isBlank()));
            assertTrue(store.isReady("kb"));
            assertEquals(1, construction.constructed().size());
        }
    }

    @Test
    @DisplayName("底层写入失败转换为 ServiceException")
    void shouldWrapAddFailure() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.add(anyList())).thenReturn(Mono.error(new IllegalStateException("boom"))))) {
            AiVectorStore store = new AiVectorStore(props());
            ServiceException ex = assertThrows(ServiceException.class,
                () -> store.add("kb", 2, List.of(doc("c1", "d1", 0, "hello"))));
            assertTrue(ex.getMessage().contains("写入向量库失败"));
            assertTrue(ex.getMessage().contains("boom"));
        }
    }

    @Test
    @DisplayName("同一集合维度不一致时拒绝写入")
    void shouldRejectDimensionMismatch() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.add(anyList())).thenReturn(Mono.empty()))) {
            AiVectorStore store = new AiVectorStore(props());
            store.add("kb", 2, List.of(doc("c1", "d1", 0, "hello")));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> store.add("kb", 4, List.of(doc("c2", "d1", 1, "world"))));
            assertTrue(ex.getMessage().contains("不一致"));
        }
    }

    @Test
    @DisplayName("构建 Milvus 集合失败转换为 ServiceException")
    void shouldWrapStoreBuildFailure() {
        AiProperties props = props();
        props.getMilvus().setUri("");
        AiVectorStore store = new AiVectorStore(props);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> store.add("kb", 2, List.of(doc("c1", "d1", 0, "hello"))));
        assertTrue(ex.getMessage().contains("连接 Milvus 失败"));
    }

    @Test
    @DisplayName("检索命中映射为 VectorHit（含 chunk_index 各种类型）")
    void shouldSearchAndMapHits() {
        List<Document> hits = List.of(
            hitDoc("c1", "d1", 3, 0.9),
            hitDoc("c2", "d2", "7", 0.5),
            hitDoc("c3", "d3", "not-number", 0.4));
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.search(any(SearchDocumentDto.class))).thenReturn(Mono.just(hits)))) {
            AiVectorStore store = new AiVectorStore(props());
            List<AiVectorStore.VectorHit> result =
                store.search("kb", 2, new double[]{1.0, 2.0}, 5, 0.1);
            assertEquals(3, result.size());
            assertEquals(new AiVectorStore.VectorHit("c1", "d1", 3, 0.9), result.get(0));
            assertEquals(Integer.valueOf(7), result.get(1).chunkIndex());
            assertNull(result.get(2).chunkIndex());
        }
    }

    @Test
    @DisplayName("检索返回 null 时返回空列表")
    void shouldReturnEmptyWhenSearchNull() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.search(any(SearchDocumentDto.class))).thenReturn(Mono.empty()))) {
            AiVectorStore store = new AiVectorStore(props());
            assertTrue(store.search("kb", 2, new double[]{1.0, 2.0}, 5, 0.1).isEmpty());
        }
    }

    @Test
    @DisplayName("检索结果缺少元数据时跳过该条")
    void shouldSkipHitWithoutMetadata() {
        Document noMeta = mock(Document.class);
        when(noMeta.getMetadata()).thenReturn(null);
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.search(any(SearchDocumentDto.class))).thenReturn(Mono.just(List.of(noMeta))))) {
            AiVectorStore store = new AiVectorStore(props());
            assertTrue(store.search("kb", 2, new double[]{1.0, 2.0}, 5, 0.1).isEmpty());
        }
    }

    @Test
    @DisplayName("底层检索失败转换为 ServiceException")
    void shouldWrapSearchFailure() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.search(any(SearchDocumentDto.class)))
                .thenReturn(Mono.error(new RuntimeException("search-boom"))))) {
            AiVectorStore store = new AiVectorStore(props());
            ServiceException ex = assertThrows(ServiceException.class,
                () -> store.search("kb", 2, new double[]{1.0, 2.0}, 5, 0.1));
            assertTrue(ex.getMessage().contains("向量检索失败"));
        }
    }

    @Test
    @DisplayName("删除：空集合返回 0，正常统计真实删除数并跳过空主键")
    void shouldDeleteAndCount() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.delete(anyString())).thenReturn(Mono.just(true)))) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(0, store.delete("kb", 2, null));
            assertEquals(0, store.delete("kb", 2, List.of()));
            assertEquals(2, store.delete("kb", 2, List.of("id1", " ", "id2")));
        }
    }

    @Test
    @DisplayName("删除失败被吞掉不影响其它主键")
    void shouldTolerateDeleteFailure() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.delete(anyString()))
                .thenReturn(Mono.error(new RuntimeException("del-boom"))))) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(0, store.delete("kb", 2, List.of("id1", "id2")));
        }
    }

    @Test
    @DisplayName("删除返回 false 时不计入删除数")
    void shouldNotCountFalseDelete() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.delete(anyString())).thenReturn(Mono.just(false)))) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(0, store.delete("kb", 2, List.of("id1")));
        }
    }

    @Test
    @DisplayName("关闭释放全部连接并清空缓存")
    void shouldCloseAllStores() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> when(mock.add(anyList())).thenReturn(Mono.empty()))) {
            AiVectorStore store = new AiVectorStore(props());
            store.add("kb", 2, List.of(doc("c1", "d1", 0, "hello")));
            MilvusStore handle = construction.constructed().get(0);
            store.close();
            verify(handle).close();
            assertFalse(store.isReady("kb"));
        }
    }

    private Document hitDoc(String chunkId, String docId, Object chunkIndex, double score) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_index", chunkIndex);
        DocumentMetadata metadata = new DocumentMetadata(
            TextBlock.builder().text("t").build(), docId, chunkId, payload);
        Document document = new Document(metadata);
        document.setScore(score);
        return document;
    }

}