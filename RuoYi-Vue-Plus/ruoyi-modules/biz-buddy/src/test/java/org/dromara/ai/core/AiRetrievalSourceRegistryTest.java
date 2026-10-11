package org.dromara.ai.core;

import org.dromara.ai.domain.vo.AiSourceVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiRetrievalSourceRegistry} 单元测试：put / drain（取出即删）与 TTL 兜底清理。
 */
@DisplayName("检索来源注册表")
class AiRetrievalSourceRegistryTest {

    private static AiSourceVo source(Long docId, Integer chunkIndex) {
        AiSourceVo vo = new AiSourceVo();
        vo.setDocId(docId);
        vo.setChunkIndex(chunkIndex);
        vo.setDocName("文档" + docId);
        return vo;
    }

    @Test
    @DisplayName("put 后 drain 取回来源，且取出即删")
    void shouldPutAndDrainOnce() {
        AiRetrievalSourceRegistry registry = new AiRetrievalSourceRegistry();
        registry.put("call-1", List.of(source(11L, 0)));

        List<AiSourceVo> first = registry.drain("call-1");
        assertEquals(1, first.size());
        assertEquals(11L, first.get(0).getDocId());
        assertTrue(registry.drain("call-1").isEmpty(), "取出后应被删除");
    }

    @Test
    @DisplayName("空 key 或空集合的 put / drain 均安全返回空")
    void shouldIgnoreBlankKeyAndEmptySources() {
        AiRetrievalSourceRegistry registry = new AiRetrievalSourceRegistry();
        registry.put(null, List.of(source(1L, 0)));
        registry.put(" ", List.of(source(1L, 0)));
        registry.put("call-1", List.of());
        registry.put("call-1", null);

        assertTrue(registry.drain("call-1").isEmpty());
        assertTrue(registry.drain(null).isEmpty());
        assertTrue(registry.drain("").isEmpty());
    }

    @Test
    @DisplayName("超过 TTL 的注册项在下一次 put 时被兜底清理")
    void shouldCleanupExpiredEntries() {
        // TTL 为负：任何已存在项在下一次 put 时即视为过期（避免依赖真实时间流逝）
        AiRetrievalSourceRegistry registry = new AiRetrievalSourceRegistry(-1L);
        registry.put("old", List.of(source(1L, 0)));
        registry.put("new", List.of(source(2L, 0)));

        assertTrue(registry.drain("old").isEmpty(), "过期项应被清理");
        assertEquals(1, registry.drain("new").size());
    }
}
