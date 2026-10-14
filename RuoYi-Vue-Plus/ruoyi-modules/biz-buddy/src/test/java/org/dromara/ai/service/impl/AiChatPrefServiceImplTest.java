package org.dromara.ai.service.impl;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiSlotSet;
import org.dromara.ai.core.agent.AiSlotStore;
import org.dromara.ai.domain.bo.AiChatPrefBo;
import org.dromara.ai.domain.vo.AiChatPrefVo;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link AiChatPrefServiceImpl} 单元测试：三段值回显、写入 / 清空与非法值拦截。
 */
@DisplayName("对话偏好服务")
class AiChatPrefServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String USER_KEY = "bizbuddy:ai:slots:user:1";

    private final AiProperties aiProperties = new AiProperties();
    private final AiSlotStore slotStore = new AiSlotStore(aiProperties);
    private final AiChatPrefServiceImpl service = new AiChatPrefServiceImpl(slotStore);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("回显平台默认 / 已设置 / 实际生效三段值")
    void shouldReturnThreeSegments() {
        aiProperties.getChat().setCurrentTimeZone("Asia/Shanghai");
        AiSlotSet user = new AiSlotSet("Asia/Shanghai", null, "en-US", null);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);
            redis.when(() -> RedisUtils.getCacheObject(USER_KEY)).thenReturn(JsonUtils.toJsonString(user));

            AiChatPrefVo vo = service.getPref();
            assertEquals("Asia/Shanghai", vo.getPlatformTimezone());
            assertEquals("zh-CN", vo.getPlatformLocale());
            assertEquals("Asia/Shanghai", vo.getTimezone());
            assertEquals("en-US", vo.getLocale());
            assertEquals("Asia/Shanghai", vo.getEffectiveTimezone());
            assertEquals("en-US", vo.getEffectiveLocale());
        }
    }

    @Test
    @DisplayName("未设置时字段为空并回落平台默认")
    void shouldFallBackToPlatformWhenNotSet() {
        aiProperties.getChat().setCurrentTimeZone("UTC");
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);

            AiChatPrefVo vo = service.getPref();
            assertNull(vo.getTimezone());
            assertNull(vo.getLocale());
            assertEquals("UTC", vo.getEffectiveTimezone());
            assertEquals("zh-CN", vo.getEffectiveLocale());
        }
    }

    @Test
    @DisplayName("合法时区与语言写入人级槽位")
    void shouldWriteUserSlot() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);

            AiChatPrefBo bo = new AiChatPrefBo();
            bo.setTimezone("Europe/Paris");
            bo.setLocale("fr-FR");
            service.savePref(bo);

            redis.verify(() -> RedisUtils.setCacheObject(eq(USER_KEY), anyString()));
        }
    }

    @Test
    @DisplayName("两项皆空等价于清空人级槽位")
    void shouldClearWhenBothBlank() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);

            service.savePref(new AiChatPrefBo());

            redis.verify(() -> RedisUtils.deleteObject(USER_KEY));
        }
    }

    @Test
    @DisplayName("非法时区被拦截")
    void shouldRejectInvalidTimezone() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);

            AiChatPrefBo bo = new AiChatPrefBo();
            bo.setTimezone("Mars/Base");
            assertThrows(ServiceException.class, () -> service.savePref(bo));
        }
    }

    @Test
    @DisplayName("非法语言被拦截")
    void shouldRejectInvalidLocale() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);

            AiChatPrefBo bo = new AiChatPrefBo();
            bo.setLocale("zh_CN");
            assertThrows(ServiceException.class, () -> service.savePref(bo));
        }
    }

    @Test
    @DisplayName("清空人级设置删除键")
    void shouldClearPref() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
             MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);

            service.clearPref();

            redis.verify(() -> RedisUtils.deleteObject(USER_KEY));
        }
    }

}
