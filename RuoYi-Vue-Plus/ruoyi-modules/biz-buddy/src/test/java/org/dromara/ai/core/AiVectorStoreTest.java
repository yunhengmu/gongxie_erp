package org.dromara.ai.core;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.MilvusStore;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("删除：空集合返回 0，按主键过滤删除并统计真实条数")
    void shouldDeleteAndCount() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> stubDelete(mock, 2L))) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(0, store.delete("kb", 2, null));
            assertEquals(0, store.delete("kb", 2, List.of()));
            assertEquals(2, store.delete("kb", 2, List.of("id1", " ", "id2")));

            ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
            MilvusClientV2 client = construction.constructed().get(0).getClient();
            verify(client).delete(captor.capture());
            assertEquals("id in [\"id1\",\"id2\"]", captor.getValue().getFilter());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("删除失败被吞掉不影响其它主键")
    void shouldTolerateDeleteFailure() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> {
                MilvusClientV2 client = mock(MilvusClientV2.class);
                when(mock.getClient()).thenReturn(client);
                when(client.delete(any(DeleteReq.class))).thenThrow(new RuntimeException("del-boom"));
            })) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(0, store.delete("kb", 2, List.of("id1", "id2")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("删除条数为 0 时不计入删除数")
    void shouldNotCountZeroDelete() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> stubDelete(mock, 0L))) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(0, store.delete("kb", 2, List.of("id1")));
        }
    }

    @Test
    @DisplayName("按文档删除：先按 doc_id 反查全部主键，再按主键清理（覆盖历史孤儿）")
    void shouldDeleteByDocIdIncludingOrphans() {
        try (MockedConstruction<MilvusStore> construction = mockConstruction(MilvusStore.class,
            (mock, context) -> {
                MilvusClientV2 client = mock(MilvusClientV2.class);
                when(mock.getClient()).thenReturn(client);
                QueryResp.QueryResult first = queryRow("v1");
                QueryResp.QueryResult second = queryRow("v2");
                QueryResp queryResp = mock(QueryResp.class);
                when(queryResp.getQueryResults()).thenReturn(List.of(first, second));
                when(client.query(any(QueryReq.class))).thenReturn(queryResp);
                DeleteResp response = deleteResp(2L);
                when(client.delete(any(DeleteReq.class))).thenReturn(response);
            })) {
            AiVectorStore store = new AiVectorStore(props());
            assertEquals(2, store.deleteByDocId("kb", 2, 9L));

            MilvusClientV2 client = construction.constructed().get(0).getClient();
            ArgumentCaptor<QueryReq> queryCaptor = ArgumentCaptor.forClass(QueryReq.class);
            verify(client).query(queryCaptor.capture());
            assertEquals("doc_id == \"9\"", queryCaptor.getValue().getFilter());
            ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
            verify(client).delete(deleteCaptor.capture());
            assertEquals("id in [\"v1\",\"v2\"]", deleteCaptor.getValue().getFilter());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 打桩「删除返回指定条数」。
     */
    private void stubDelete(MilvusStore store, long count) throws Exception {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(store.getClient()).thenReturn(client);
        DeleteResp response = deleteResp(count);
        when(client.delete(any(DeleteReq.class))).thenReturn(response);
    }

    private DeleteResp deleteResp(long count) {
        DeleteResp response = mock(DeleteResp.class);
        when(response.getDeleteCnt()).thenReturn(count);
        return response;
    }

    private QueryResp.QueryResult queryRow(String id) {
        QueryResp.QueryResult row = mock(QueryResp.QueryResult.class);
        when(row.getEntity()).thenReturn(Map.of("id", id));
        return row;
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