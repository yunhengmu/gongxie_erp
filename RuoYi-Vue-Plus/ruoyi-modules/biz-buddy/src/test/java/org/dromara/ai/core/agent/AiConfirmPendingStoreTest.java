package org.dromara.ai.core.agent;

import org.dromara.ai.config.AiProperties;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link AiConfirmPendingStore} 单元测试：待确认状态的暂存、读取、删除与恢复入口占用。
 */
@DisplayName("HITL 待确认暂存")
class AiConfirmPendingStoreTest {

    private final AiProperties aiProperties = new AiProperties();
    private final AiConfirmPendingStore store = new AiConfirmPendingStore(aiProperties);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    private static AiConfirmPendingStore.PendingConfirm pending() {
        return new AiConfirmPendingStore.PendingConfirm(1L, 2L, 3L, 4L, "reply-1",
            List.of(new AiConfirmPendingStore.PendingToolCall("c1", "create_notice", Map.of("a", 1), "{}")),
            List.of(new AiConfirmPendingStore.PendingDecision("c1", true, false, null)),
            "2026-01-01T00:00:00",
            new AiConfirmPendingStore.ExpertResume(9L, 8L, "9#8", "task", 1, "first"));
    }

    @Test
    @DisplayName("保存待确认状态带 TTL")
    void shouldSaveWithTtl() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.save(pending());
            redis.verify(() -> RedisUtils.setCacheObject(eq("bizbuddy:ai:confirm:pending:1:2"),
                anyString(), any(Duration.class)));
        }
    }

    @Test
    @DisplayName("读取不存在的待确认状态为空")
    void shouldReturnEmptyWhenAbsent() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject("bizbuddy:ai:confirm:pending:1:2")).thenReturn(" ");
            assertTrue(store.get(1L, 2L).isEmpty());
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            assertTrue(store.get(1L, 2L).isEmpty());
        }
    }

    @Test
    @DisplayName("读取已有待确认状态")
    void shouldReadPending() {
        String json = JsonUtils.toJsonString(pending());
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject("bizbuddy:ai:confirm:pending:1:2")).thenReturn(json);
            Optional<AiConfirmPendingStore.PendingConfirm> result = store.get(1L, 2L);
            assertTrue(result.isPresent());
            assertEquals(1L, result.get().sessionId());
            assertEquals("reply-1", result.get().replyId());
            assertEquals(1, result.get().toolCalls().size());
        }
    }

    @Test
    @DisplayName("解析失败按已过期处理")
    void shouldReturnEmptyOnParseFailure() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn("{not-a-confirm");
            assertTrue(store.get(1L, 2L).isEmpty());
        }
    }

    @Test
    @DisplayName("删除待确认状态")
    void shouldRemove() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.remove(1L, 2L);
            redis.verify(() -> RedisUtils.deleteObject("bizbuddy:ai:confirm:pending:1:2"));
        }
    }

    @Test
    @DisplayName("占用恢复入口：成功、失败与空返回")
    void shouldClaimResumeEntry() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(eq("bizbuddy:ai:confirm:claimed:1:2"),
                anyString(), any(Duration.class))).thenReturn(true);
            assertTrue(store.claim(1L, 2L));

            redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
            assertFalse(store.claim(1L, 2L));

            // setObjectIfAbsent 返回原始 boolean，不存在 null；未命中时即 false，同样视为未获得
            assertFalse(store.claim(3L, 4L));
        }
    }

    @Test
    @DisplayName("释放恢复入口占用")
    void shouldRelease() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.release(1L, 2L);
            redis.verify(() -> RedisUtils.deleteObject("bizbuddy:ai:confirm:claimed:1:2"));
        }
    }
}