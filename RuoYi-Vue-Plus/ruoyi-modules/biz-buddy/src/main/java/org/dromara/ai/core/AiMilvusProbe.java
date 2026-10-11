package org.dromara.ai.core;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Milvus 真实连接探测（REQ-AI-35 平台健康自检）。
 *
 * <p>{@link AiVectorStore} 的连接是**惰性建立**的（`isReady` 只反映内存缓存），因此「配置齐全但本进程
 * 尚未建连」时无法判定向量库是否真的可用。本类按与 {@link AiVectorStore} 相同的连接参数独立建连、
 * 列一次集合，用完即关——**不写任何数据、不污染向量库**。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMilvusProbe {

    /**
     * 探测用集合名：必定不存在，仅用于验证服务端可达与鉴权通过
     */
    private static final String PROBE_COLLECTION = "__bizbuddy_health_probe__";

    private final AiProperties aiProperties;

    /**
     * 探测一次向量库连接。
     *
     * @return 成功描述（含地址）
     * @throws ServiceException 未配置地址或连接失败
     */
    public String probe() {
        AiProperties.Milvus milvus = aiProperties.getMilvus();
        if (StringUtils.isBlank(milvus.getUri())) {
            throw new ServiceException("未配置向量库地址（bizbuddy.ai.milvus.uri）");
        }
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
            .uri(milvus.getUri())
            .connectTimeoutMs(milvus.getConnectTimeoutMs());
        if (StringUtils.isNotBlank(milvus.getUsername())) {
            builder.username(milvus.getUsername());
        }
        if (StringUtils.isNotBlank(milvus.getPassword())) {
            builder.password(milvus.getPassword());
        }
        MilvusClientV2 client = new MilvusClientV2(builder.build());
        try {
            Boolean exists = client.hasCollection(
                HasCollectionReq.builder().collectionName(PROBE_COLLECTION).build());
            return "连接成功（uri=" + milvus.getUri() + "，探测集合存在：" + Boolean.TRUE.equals(exists) + "）";
        } catch (Exception e) {
            throw new ServiceException("连接失败：" + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("关闭 Milvus 探测连接失败：{}", e.getMessage());
            }
        }
    }

}
