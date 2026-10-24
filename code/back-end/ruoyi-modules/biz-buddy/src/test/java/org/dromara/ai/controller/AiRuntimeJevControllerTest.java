package org.dromara.ai.controller;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.vo.AiJevConfigVo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRuntimeController} 结构化决策路由分组的单元测试：读写转发，以及
 * <b>「只能引用模型配置里已配置好的决策模型」</b>这一强校验（存在 / 启用 / decision 类型 / 密钥已配）。
 */
@DisplayName("AI 结构化决策配置 Controller")
class AiRuntimeJevControllerTest {

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);

    private final AiRuntimeController controller = new AiRuntimeController(store, modelConfigService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询结构化决策配置：只回带所选模型主键与判定策略，不含任何连接信息字段")
    void shouldGetJevWithoutConnectionFields() throws Exception {
        AiJevConfigBo stored = new AiJevConfigBo();
        stored.setEnabled(true);
        stored.setModelConfigId(9L);
        when(store.jev()).thenReturn(stored);

        R<AiJevConfigVo> r = controller.getJev();

        assertTrue(R.isSuccess(r));
        AiJevConfigVo vo = r.getData();
        assertEquals(9L, vo.getModelConfigId());
        // 视图对象不应存在连接信息字段，确保地址 / 模型标识 / 密钥不经本接口下发
        assertThrows(NoSuchFieldException.class, () -> AiJevConfigVo.class.getDeclaredField("baseUrl"));
        assertThrows(NoSuchFieldException.class, () -> AiJevConfigVo.class.getDeclaredField("model"));
        assertThrows(NoSuchFieldException.class, () -> AiJevConfigVo.class.getDeclaredField("apiKey"));
    }

    @Test
    @DisplayName("查询未启用的配置时 enabled=false")
    void shouldGetJevDisabled() {
        AiJevConfigBo stored = new AiJevConfigBo();
        when(store.jev()).thenReturn(stored);
        assertFalse(controller.getJev().getData().isEnabled());
    }

    @Test
    @DisplayName("已开启但未选择决策模型：保存被拦下")
    void shouldRejectWhenEnabledWithoutModel() {
        AiJevConfigBo bo = new AiJevConfigBo();
        bo.setEnabled(true);
        ServiceException error = assertThrows(ServiceException.class, () -> controller.updateJev(bo));
        assertTrue(error.getMessage().contains("请先选择一条决策模型"));
    }

    @Test
    @DisplayName("所选决策模型不存在：保存被拦下")
    void shouldRejectWhenModelMissing() {
        AiJevConfigBo bo = enabledWith(9L);
        when(modelConfigService.queryById(9L)).thenReturn(null);
        ServiceException error = assertThrows(ServiceException.class, () -> controller.updateJev(bo));
        assertTrue(error.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("所选决策模型已停用：保存被拦下")
    void shouldRejectWhenModelDisabled() {
        AiJevConfigBo bo = enabledWith(9L);
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("1", AiModelConfig.TYPE_DECISION));
        ServiceException error = assertThrows(ServiceException.class, () -> controller.updateJev(bo));
        assertTrue(error.getMessage().contains("已停用"));
    }

    @Test
    @DisplayName("所选模型不是 decision 类型：保存被拦下")
    void shouldRejectWhenModelTypeMismatch() {
        AiJevConfigBo bo = enabledWith(9L);
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0", AiModelConfig.TYPE_CHAT));
        ServiceException error = assertThrows(ServiceException.class, () -> controller.updateJev(bo));
        assertTrue(error.getMessage().contains("不是决策模型"));
    }

    @Test
    @DisplayName("所选决策模型未配置密钥：保存被拦下并指向「模型配置」")
    void shouldRejectWhenApiKeyMissing() {
        AiJevConfigBo bo = enabledWith(9L);
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0", AiModelConfig.TYPE_DECISION));
        when(modelConfigService.decryptApiKey(9L)).thenReturn(null);
        ServiceException error = assertThrows(ServiceException.class, () -> controller.updateJev(bo));
        assertTrue(error.getMessage().contains("模型配置"));
        assertTrue(error.getMessage().contains("密钥"));
    }

    @Test
    @DisplayName("校验通过：转发保存，且关闭态不校验模型可用性")
    void shouldSaveWhenModelUsable() {
        AiJevConfigBo bo = enabledWith(9L);
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0", AiModelConfig.TYPE_DECISION));
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk-decision");

        assertTrue(R.isSuccess(controller.updateJev(bo)));
        ArgumentCaptor<AiJevConfigBo> captor = ArgumentCaptor.forClass(AiJevConfigBo.class);
        verify(store).updateJev(captor.capture());
        assertEquals(9L, captor.getValue().getModelConfigId());

        // 关闭态：允许保留一个已选 id 供下次开启，不做可用性校验
        AiJevConfigBo off = new AiJevConfigBo();
        off.setEnabled(false);
        off.setModelConfigId(9L);
        assertTrue(R.isSuccess(controller.updateJev(off)));
    }

    /**
     * 构造「已开启 + 指定模型」的结构化决策配置。
     *
     * @param modelConfigId 所选决策模型主键
     * @return 配置
     */
    private AiJevConfigBo enabledWith(Long modelConfigId) {
        AiJevConfigBo bo = new AiJevConfigBo();
        bo.setEnabled(true);
        bo.setModelConfigId(modelConfigId);
        return bo;
    }

    /**
     * 构造模型配置（「模型配置」中的一条记录）。
     *
     * @param status    状态（0 正常 / 1 停用）
     * @param modelType 模型类型
     * @return 模型配置
     */
    private AiModelConfigVo decisionModel(String status, String modelType) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(9L);
        vo.setConfigName("百炼决策模型");
        vo.setModelType(modelType);
        vo.setModelCode("decision-model-preview");
        vo.setBaseUrl("https://ws.cn-beijing.maas.aliyuncs.com/compatible-mode");
        vo.setStatus(status);
        return vo;
    }

}