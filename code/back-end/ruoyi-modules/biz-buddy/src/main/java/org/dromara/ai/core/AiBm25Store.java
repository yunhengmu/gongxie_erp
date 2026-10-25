package org.dromara.ai.core;

import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 原生 BM25 全文索引门面（按知识库集合隔离）。
 *
 * <p>每个知识库对应一个独立集合 {@code <collection>_bm25}：正文由 Milvus 的 BM25 Function
 * 在服务端生成稀疏向量（无需平台侧调模型），检索时直接传查询文本即可。集合按需创建，
 * 与稠密向量集合（{@code AiVectorStore}）分开存放，避免改写既有集合的 Schema。</p>
 *
 * <p><b>边界</b>：集合不存在时检索返回空、删除返回 0（视为「未构建」），由调用方降级为纯向量检索。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiBm25Store {

    /**
     * BM25 集合名后缀
     */
    private static final String COLLECTION_SUFFIX = "_bm25";

    /**
     * 正文字段（启用分词器，索引与检索都走它）
     */
    private static final String CONTENT_FIELD = "content";

    /**
     * 稀疏向量字段（由 BM25 Function 写入）
     */
    private static final String SPARSE_FIELD = "sparse";

    private final AiProperties aiProperties;

    /**
     * Milvus 客户端（延迟创建；BM25 未启用时不建立连接）
     */
    private volatile MilvusClientV2 client;

    /**
     * 已确认存在的集合（避免每次写入都探测）
     */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    /**
     * BM25 集合名称。
     *
     * @param collection 稠密向量集合名
     * @return BM25 集合名
     */
    private String collectionName(String collection) {
        return collection + COLLECTION_SUFFIX;
    }

    /**
     * 写入切片正文（稀疏向量由服务端 BM25 Function 生成）。
     *
     * @param collection 稠密向量集合名
     * @param docs       切片
     */
    public void add(String collection, List<Bm25Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        String name = collectionName(collection);
        ensureCollection(name);
        List<JsonObject> rows = new ArrayList<>(docs.size());
        for (Bm25Doc doc : docs) {
            JsonObject row = new JsonObject();
            row.addProperty("id", doc.chunkId());
            row.addProperty("doc_id", doc.docId());
            row.addProperty("chunk_index", doc.chunkIndex());
            row.addProperty(CONTENT_FIELD, doc.content());
            rows.add(row);
        }
        try {
            client().insert(InsertReq.builder().collectionName(name).data(rows).build());
            flushQuietly(name);
        } catch (Exception e) {
            throw new ServiceException("写入 BM25 索引失败：" + rootMessage(e));
        }
    }

    /**
     * 按查询文本做 BM25 检索。
     *
     * @param collection 稠密向量集合名
     * @param query      查询文本
     * @param topK       召回条数
     * @return 命中切片（按 BM25 分降序）；集合不存在时返回空
     */
    public List<Bm25Hit> search(String collection, String query, int topK) {
        if (StringUtils.isBlank(query) || topK <= 0) {
            return List.of();
        }
        String name = collectionName(collection);
        if (!exists(name)) {
            return List.of();
        }
        SearchResp response;
        try {
            response = client().search(SearchReq.builder()
                .collectionName(name)
                .annsField(SPARSE_FIELD)
                .data(List.of(new EmbeddedText(query)))
                .topK(topK)
                .build());
        } catch (Exception e) {
            throw new ServiceException("BM25 检索失败：" + rootMessage(e));
        }
        List<Bm25Hit> hits = new ArrayList<>();
        if (response == null || response.getSearchResults() == null) {
            return hits;
        }
        for (List<SearchResp.SearchResult> group : response.getSearchResults()) {
            for (SearchResp.SearchResult row : group) {
                String chunkId = row.getPrimaryKey();
                if (StringUtils.isBlank(chunkId) && row.getId() != null) {
                    chunkId = String.valueOf(row.getId());
                }
                if (StringUtils.isNotBlank(chunkId)) {
                    hits.add(new Bm25Hit(chunkId));
                }
            }
        }
        return hits;
    }

    /**
     * 删除指定文档在 BM25 集合中的全部切片。
     *
     * @param collection 稠密向量集合名
     * @param docId      文档主键
     * @return 实际删除条数
     */
    public int deleteByDocId(String collection, Long docId) {
        if (docId == null) {
            return 0;
        }
        String name = collectionName(collection);
        if (!exists(name)) {
            return 0;
        }
        try {
            DeleteResp response = client().delete(DeleteReq.builder()
                .collectionName(name)
                .filter("doc_id == " + docId)
                .build());
            return response == null ? 0 : (int) response.getDeleteCnt();
        } catch (Exception e) {
            log.warn("删除 BM25 索引失败，collection={}, docId={}, cause={}", name, docId, e.getMessage());
            return 0;
        }
    }

    /**
     * 删除整个 BM25 集合（知识库删除时调用）。
     *
     * @param collection 稠密向量集合名
     */
    public void dropCollection(String collection) {
        String name = collectionName(collection);
        ensured.remove(name);
        try {
            client().dropCollection(DropCollectionReq.builder().collectionName(name).build());
        } catch (Exception e) {
            log.warn("删除 BM25 集合失败，collection={}, cause={}", name, e.getMessage());
        }
    }

    /**
     * 集合是否已存在。
     *
     * @param name 完整集合名（含后缀）
     * @return 是否存在
     */
    private boolean exists(String name) {
        if (ensured.contains(name)) {
            return true;
        }
        try {
            boolean present = Boolean.TRUE.equals(client().hasCollection(
                HasCollectionReq.builder().collectionName(name).build()));
            if (present) {
                ensured.add(name);
            }
            return present;
        } catch (Exception e) {
            log.warn("探测 BM25 集合失败，collection={}, cause={}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 确保 BM25 集合存在（首次使用时按固定 Schema 创建）。
     *
     * @param name 完整集合名（含后缀）
     */
    private void ensureCollection(String name) {
        if (ensured.contains(name)) {
            return;
        }
        synchronized (ensured) {
            if (ensured.contains(name)) {
                return;
            }
            try {
                if (!Boolean.TRUE.equals(client().hasCollection(
                    HasCollectionReq.builder().collectionName(name).build()))) {
                    createCollection(name);
                }
                ensured.add(name);
            } catch (Exception e) {
                throw new ServiceException("初始化 BM25 索引失败：" + rootMessage(e));
            }
        }
    }

    /**
     * 创建 BM25 集合：正文启用中文分词，{@code sparse} 由 BM25 Function 自动生成。
     *
     * @param name 集合名
     */
    private void createCollection(String name) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
            .enableDynamicField(false)
            .build();
        schema.addField(AddFieldReq.builder()
            .fieldName("id").dataType(DataType.VarChar).maxLength(64)
            .isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder()
            .fieldName("doc_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
            .fieldName("chunk_index").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
            .fieldName(CONTENT_FIELD).dataType(DataType.VarChar).maxLength(65535)
            .enableAnalyzer(true)
            .analyzerParams(Map.of("type", "chinese"))
            .build());
        schema.addField(AddFieldReq.builder()
            .fieldName(SPARSE_FIELD).dataType(DataType.SparseFloatVector).build());
        schema.addFunction(CreateCollectionReq.Function.builder()
            .name("bm25")
            .functionType(FunctionType.BM25)
            .inputFieldNames(List.of(CONTENT_FIELD))
            .outputFieldNames(List.of(SPARSE_FIELD))
            .build());
        IndexParam sparseIndex = IndexParam.builder()
            .fieldName(SPARSE_FIELD)
            .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
            .metricType(IndexParam.MetricType.BM25)
            .build();
        client().createCollection(CreateCollectionReq.builder()
            .collectionName(name)
            .description("BizBuddy BM25 index")
            .collectionSchema(schema)
            .indexParams(List.of(sparseIndex))
            .build());
        log.info("BM25 集合已创建：{}", name);
    }

    /**
     * 尽力刷盘，让刚写入的切片立即可检索。
     *
     * @param name 集合名
     */
    private void flushQuietly(String name) {
        try {
            client().flush(FlushReq.builder()
                .collectionNames(List.of(name))
                .waitFlushedTimeoutMs(10_000L)
                .build());
        } catch (Exception e) {
            log.warn("BM25 flush 失败（不影响最终可见性），collection={}, cause={}", name, e.getMessage());
        }
    }

    /**
     * 获取或创建 Milvus 客户端。
     *
     * @return 客户端
     */
    private MilvusClientV2 client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    AiProperties.Milvus milvus = aiProperties.getMilvus();
                    client = new MilvusClientV2(ConnectConfig.builder()
                        .uri(milvus.getUri())
                        .username(milvus.getUsername())
                        .password(milvus.getPassword())
                        .connectTimeoutMs(milvus.getConnectTimeoutMs())
                        .build());
                }
            }
        }
        return client;
    }

    /**
     * 提取根因信息。
     *
     * @param e 异常
     * @return 可读信息
     */
    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StringUtils.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }

    /**
     * 释放 Milvus 连接。
     */
    @PreDestroy
    public void close() {
        MilvusClientV2 current = client;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                log.warn("关闭 BM25 Milvus 连接失败：{}", e.getMessage());
            }
        }
        ensured.clear();
    }

    /**
     * 待写入 BM25 索引的切片。
     *
     * @param chunkId    切片主键
     * @param docId      文档主键
     * @param chunkIndex 切片序号
     * @param content    切片正文
     */
    public record Bm25Doc(String chunkId, Long docId, Integer chunkIndex, String content) {
    }

    /**
     * BM25 命中结果（仅保留主键，融合只使用排名）。
     *
     * @param chunkId 切片主键
     */
    public record Bm25Hit(String chunkId) {
    }

}
