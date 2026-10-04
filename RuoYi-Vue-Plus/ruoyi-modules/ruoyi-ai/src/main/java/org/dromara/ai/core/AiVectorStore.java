package org.dromara.ai.core;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.MilvusStore;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.milvus.v2.common.IndexParam;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 向量库门面。
 *
 * <p>每个知识库对应一个 Milvus 集合；集合按需自动创建并缓存连接。
 * 向量存储使用 AgentScope 的 {@link MilvusStore}（无需自建向量库适配）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiVectorStore {

    /**
     * 向量字段名（MilvusStore 约定的默认值）
     */
    private static final String VECTOR_FIELD = "vector";

    private final AiProperties aiProperties;

    /**
     * 集合名 → 向量库句柄
     */
    private final Map<String, MilvusStore> stores = new ConcurrentHashMap<>();

    /**
     * 集合名 → 向量维度（用于校验同一集合的维度一致性）
     */
    private final Map<String, Integer> dimensions = new ConcurrentHashMap<>();

    /**
     * 写入向量。
     *
     * @param collection 集合名
     * @param dimension  向量维度
     * @param docs       待写入切片
     * @return 向量主键列表，顺序与入参一致（由 AgentScope 依内容生成，用于后续删除与重建）
     */
    public List<String> add(String collection, int dimension, List<VectorDoc> docs) {
        List<String> ids = new ArrayList<>(docs.size());
        if (docs.isEmpty()) {
            return ids;
        }
        List<Document> documents = new ArrayList<>(docs.size());
        for (VectorDoc doc : docs) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("doc_id", doc.docId());
            payload.put("chunk_index", doc.chunkIndex());
            payload.put("doc_name", doc.docName());
            DocumentMetadata metadata = DocumentMetadata.builder()
                .content(TextBlock.builder().text(doc.content()).build())
                .docId(doc.docId())
                .chunkId(doc.chunkId())
                .payload(payload)
                .build();
            Document document = new Document(metadata);
            document.setEmbedding(doc.vector());
            document.setVectorName(VECTOR_FIELD);
            documents.add(document);
            ids.add(document.getId());
        }
        try {
            store(collection, dimension).add(documents).block();
        } catch (Exception e) {
            throw new ServiceException("写入向量库失败：" + rootMessage(e, collection));
        }
        return ids;
    }

    /**
     * 相似度检索。
     *
     * @param collection 集合名
     * @param dimension  向量维度
     * @param query      查询向量
     * @param topK       召回条数
     * @param threshold  相似度阈值
     * @return 命中切片（按相似度降序）
     */
    public List<VectorHit> search(String collection, int dimension, double[] query, int topK, double threshold) {
        SearchDocumentDto dto = SearchDocumentDto.builder()
            .queryEmbedding(query)
            .limit(topK)
            .scoreThreshold(threshold)
            .vectorName(VECTOR_FIELD)
            .build();
        List<Document> documents;
        try {
            documents = store(collection, dimension).search(dto).block();
        } catch (Exception e) {
            throw new ServiceException("向量检索失败：" + rootMessage(e, collection));
        }
        List<VectorHit> hits = new ArrayList<>();
        if (documents == null) {
            return hits;
        }
        for (Document document : documents) {
            DocumentMetadata metadata = document.getMetadata();
            if (metadata == null) {
                continue;
            }
            Map<String, Object> payload = metadata.getPayload();
            hits.add(new VectorHit(
                metadata.getChunkId(),
                metadata.getDocId(),
                payload == null ? null : asInteger(payload.get("chunk_index")),
                document.getScore()));
        }
        return hits;
    }

    /**
     * 按向量主键批量删除。
     *
     * @param collection 集合名
     * @param dimension  向量维度
     * @param ids        向量主键集合
     * @return 实际删除条数
     */
    public int delete(String collection, int dimension, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        MilvusStore store = store(collection, dimension);
        int count = 0;
        for (String id : ids) {
            if (StringUtils.isBlank(id)) {
                continue;
            }
            try {
                Boolean deleted = store.delete(id).block();
                if (Boolean.TRUE.equals(deleted)) {
                    count++;
                }
            } catch (Exception e) {
                // 删除失败不阻断业务，记录后由「重建索引」兜底
                log.warn("删除向量失败，collection={}, id={}, cause={}", collection, id, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 集合是否已建立连接。
     *
     * @param collection 集合名
     * @return 是否可用
     */
    public boolean isReady(String collection) {
        return stores.containsKey(collection);
    }

    /**
     * 获取或创建集合句柄。
     *
     * @param collection 集合名
     * @param dimension  向量维度
     * @return 向量库句柄
     */
    private MilvusStore store(String collection, int dimension) {
        Integer existing = dimensions.get(collection);
        if (existing != null && existing != dimension) {
            throw new ServiceException("向量集合 " + collection + " 已按维度 " + existing
                + " 建立，与当前维度 " + dimension + " 不一致，请先清空该知识库文档后重建");
        }
        return stores.computeIfAbsent(collection, name -> {
            AiProperties.Milvus milvus = aiProperties.getMilvus();
            try {
                MilvusStore built = MilvusStore.builder()
                    .uri(milvus.getUri())
                    .collectionName(name)
                    .dimensions(dimension)
                    .metricType(IndexParam.MetricType.COSINE)
                    .connectTimeoutMs(milvus.getConnectTimeoutMs())
                    .username(milvus.getUsername())
                    .password(milvus.getPassword())
                    .build();
                dimensions.put(name, dimension);
                log.info("Milvus 集合已就绪：{}（维度 {}）", name, dimension);
                return built;
            } catch (Exception e) {
                throw new ServiceException("连接 Milvus 失败（" + milvus.getUri() + "）：" + rootMessage(e, name));
            }
        });
    }

    /**
     * 提取根因信息，便于页面直接定位问题。
     *
     * @param e          异常
     * @param collection 集合名
     * @return 可读信息
     */
    private String rootMessage(Throwable e, String collection) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StringUtils.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }

    /**
     * 转换 payload 中的数值。
     *
     * @param value 原始值
     * @return 整型值
     */
    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 释放全部 Milvus 连接。
     */
    @PreDestroy
    public void close() {
        stores.values().forEach(store -> {
            try {
                store.close();
            } catch (Exception e) {
                log.warn("关闭 Milvus 连接失败：{}", e.getMessage());
            }
        });
        stores.clear();
        dimensions.clear();
    }

    /**
     * 待写入向量库的切片。
     *
     * @param chunkId    切片主键（同时作为业务侧向量标识）
     * @param docId      文档主键
     * @param docName    文档名称
     * @param chunkIndex 切片序号
     * @param content    切片正文
     * @param vector     向量
     */
    public record VectorDoc(String chunkId, String docId, String docName, Integer chunkIndex,
                            String content, double[] vector) {
    }

    /**
     * 检索命中结果。
     *
     * @param chunkId    切片主键
     * @param docId      文档主键
     * @param chunkIndex 切片序号
     * @param score      相似度得分
     */
    public record VectorHit(String chunkId, String docId, Integer chunkIndex, Double score) {
    }

}