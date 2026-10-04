package org.dromara.ai.core.agent;

import org.dromara.ai.config.AiProperties;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link AiWriteDedupStore} 单元测试：去重开关、查询、登记与失败放行。
 */
@DisplayName("专家写工具去重登记")
class AiWriteDedupStoreTest {

    private final AiProperties aiProperties = new AiProperties();
    private final AiWriteDedupStore store = new AiWriteDedupStore(aiProperties);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("开关与作用域缺失时视为未执行且不登记")
    void shouldSkipWhenDisabledOrScopeBlank() {
        aiProperties.getChat().setExpertWriteDedupEnabled(false);
        assertFalse(store.enabled());
        assertFalse(store.seen("scope", "tool", Map.of()));
        store.remember("scope", "tool", Map.of());

        aiProperties.getChat().setExpertWriteDedupEnabled(true);
        assertTrue(store.enabled());
        assertFalse(store.seen(" ", "tool", Map.of()));
        store.remember(null, "tool", Map.of());

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("已登记的写调用返回已执行")
    void shouldDetectSeen() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn("1");
            assertTrue(store.seen("scope", "tool", Map.of("a", 1)));
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            assertFalse(store.seen("scope", "tool", Map.of("a", 1)));
        }
    }

    @Test
    @DisplayName("查询异常按未执行放行")
    void shouldSwallowSeenFailure() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenThrow(new RuntimeException("redis down"));
            assertFalse(store.seen("scope", "tool", Map.of()));
        }
    }

    @Test
    @DisplayName("登记成功写入带 TTL 的键")
    void shouldRememberWithTtl() {
        aiProperties.getChat().setExpertWriteDedupTtlSeconds(100);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.remember("scope", "tool", Map.of("a", 1));
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), eq("1"), ttl.capture()));
            assertEquals(100L, ttl.getValue().getSeconds());
        }
    }

    @Test
    @DisplayName("TTL 缺省用 1800 秒，超上限截断为 86400 秒")
    void shouldClampTtl() {
        aiProperties.getChat().setExpertWriteDedupTtlSeconds(0);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.remember("scope", "tool", Map.of());
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), eq("1"), ttl.capture()));
            assertEquals(1800L, ttl.getValue().getSeconds());
        }

        aiProperties.getChat().setExpertWriteDedupTtlSeconds(999_999L);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.remember("scope", "tool", Map.of());
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), eq("1"), ttl.capture()));
            assertEquals(86_400L, ttl.getValue().getSeconds());
        }
    }

    @Test
    @DisplayName("登记异常不影响本次执行")
    void shouldSwallowRememberFailure() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
            store.remember("scope", "tool", Map.of());
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class)));
        }
    }

    @Test
    @DisplayName("入参为空时仍按未执行处理")
    void shouldHandleNullInput() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            assertFalse(store.seen("scope", "tool", null));
            redis.verify(() -> RedisUtils.getCacheObject(anyString()));
        }
    }
}