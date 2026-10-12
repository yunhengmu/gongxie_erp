package org.dromara.ai.controller;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.vo.AiJevConfigVo;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.R;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRuntimeController} 结构化决策路由分组的单元测试：读写转发、「密钥留空不修改」与「不回显密钥」。
 */
@DisplayName("AI 结构化决策配置 Controller")
class AiRuntimeJevControllerTest {

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final AiRuntimeController controller = new AiRuntimeController(store);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询结构化决策配置不回显密钥，只返回「是否已配置」")
    void shouldGetJevWithoutLeakingKey() {
        when(store.jev()).thenReturn(jev(true, "sk-secret-value", "https://example.com/compatible-mode"));
        R<AiJevConfigVo> r = controller.getJev();
        assertTrue(R.isSuccess(r));
        AiJevConfigVo vo = r.getData();
        assertTrue(vo.isApiKeyConfigured(), "已配置密钥时应返回 apiKeyConfigured=true");
        // 视图对象不应存在 apiKey 字段，确保密钥不会下发前端
        assertThrows(NoSuchFieldException.class, () -> AiJevConfigVo.class.getDeclaredField("apiKey"));
        assertEquals("https://example.com/compatible-mode", vo.getBaseUrl());
    }

    @Test
    @DisplayName("查询未启用且未配密钥的配置时 enabled=false / apiKeyConfigured=false")
    void shouldGetJevDisabled() {
        when(store.jev()).thenReturn(jev(false, "", ""));
        AiJevConfigVo vo = controller.getJev().getData();
        assertFalse(vo.isEnabled());
        assertFalse(vo.isApiKeyConfigured());
    }

    @Test
    @DisplayName("保存时空密钥沿用已保存的密钥（留空＝不修改）")
    void shouldKeepExistingKeyWhenBlank() {
        when(store.jev()).thenReturn(jev(true, "old-secret", "https://example.com/compatible-mode"));
        AiJevConfigBo bo = jev(true, "", "https://example.com/compatible-mode");
        assertTrue(R.isSuccess(controller.updateJev(bo)));
        ArgumentCaptor<AiJevConfigBo> captor = ArgumentCaptor.forClass(AiJevConfigBo.class);
        verify(store).updateJev(captor.capture());
        assertEquals("old-secret", captor.getValue().getApiKey());
    }

    @Test
    @DisplayName("保存时传新密钥则使用新值")
    void shouldUseNewKeyWhenProvided() {
        AiJevConfigBo bo = jev(true, "new-secret", "https://example.com/compatible-mode");
        assertTrue(R.isSuccess(controller.updateJev(bo)));
        ArgumentCaptor<AiJevConfigBo> captor = ArgumentCaptor.forClass(AiJevConfigBo.class);
        verify(store).updateJev(captor.capture());
        assertEquals("new-secret", captor.getValue().getApiKey());
        // 传了新密钥，无需回读旧值
        verify(store, never()).jev();
    }

    /**
     * 构造结构化决策配置（仅用于转发测试）。
     */
    private AiJevConfigBo jev(boolean enabled, String apiKey, String baseUrl) {
        AiJevConfigBo bo = new AiJevConfigBo();
        bo.setEnabled(enabled);
        bo.setApiKey(apiKey);
        bo.setBaseUrl(baseUrl);
        return bo;
    }

}