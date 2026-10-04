package org.dromara.ai.core.agent;

import org.dromara.ai.config.AiProperties;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * {@link AiSlotStore} 单元测试：两级槽位合并、读取降级与写入覆盖。
 */
@DisplayName("继承槽位读写")
class AiSlotStoreTest {

    private static final String USER_KEY = "bizbuddy:ai:slots:user:1";
    private static final String SESSION_KEY = "bizbuddy:ai:slots:session:2";

    private final AiProperties aiProperties = new AiProperties();
    private final AiSlotStore store = new AiSlotStore(aiProperties);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("用户与会话均为空时取平台默认")
    void shouldUsePlatformDefault() {
        aiProperties.getChat().setCurrentTimeZone("Asia/Shanghai");
        AiSlotSet slots = store.merge(null, null);
        assertEquals("Asia/Shanghai", slots.timezone());
        assertEquals("zh-CN", slots.locale());
    }

    @Test
    @DisplayName("会话级覆盖人级覆盖平台默认")
    void shouldOverlayUserThenSession() {
        AiSlotSet userSlots = new AiSlotSet(null, null, "en-US", null);
        AiSlotSet sessionSlots = new AiSlotSet(null, "近 30 天", null, "任务线B");
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(USER_KEY)).thenReturn(JsonUtils.toJsonString(userSlots));
            redis.when(() -> RedisUtils.getCacheObject(SESSION_KEY)).thenReturn(JsonUtils.toJsonString(sessionSlots));
            AiSlotSet slots = store.merge(1L, 2L);
            assertEquals("en-US", slots.locale());
            assertEquals("近 30 天", slots.timeWindow());
            assertEquals("任务线B", slots.taskLine());
        }
    }

    @Test
    @DisplayName("读取异常时按无槽位降级")
    void shouldDegradeWhenReadFails() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenThrow(new RuntimeException("redis down"));
            AiSlotSet slots = store.merge(1L, 2L);
            assertEquals(aiProperties.getChat().getCurrentTimeZone(), slots.timezone());
            assertEquals("zh-CN", slots.locale());
        }
    }

    @Test
    @DisplayName("空槽位删除键，非空槽位写入")
    void shouldWriteOrDeleteUserSlot() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.setUserSlot(1L, null);
            store.setUserSlot(1L, AiSlotSet.empty());
            redis.verify(() -> RedisUtils.deleteObject(USER_KEY), times(2));

            store.setUserSlot(1L, new AiSlotSet("Asia/Shanghai", null, "zh-CN", null));
            redis.verify(() -> RedisUtils.setCacheObject(eq(USER_KEY), anyString()));
        }
    }

    @Test
    @DisplayName("会话级槽位写入与删除")
    void shouldWriteOrDeleteSessionSlot() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.setSessionSlot(2L, new AiSlotSet(null, "近 7 天", null, null));
            redis.verify(() -> RedisUtils.setCacheObject(eq(SESSION_KEY), anyString()));

            store.setSessionSlot(2L, AiSlotSet.empty());
            redis.verify(() -> RedisUtils.deleteObject(SESSION_KEY));
        }
    }
}