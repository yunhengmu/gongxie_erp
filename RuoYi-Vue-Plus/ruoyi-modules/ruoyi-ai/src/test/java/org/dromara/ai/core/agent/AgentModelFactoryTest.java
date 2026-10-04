package org.dromara.ai.core.agent;

import io.agentscope.core.model.Model;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentModelFactory} 单元测试：模型槽位解析、子智能体模型还原与模型构造。
 */
@DisplayName("智能体模型构造工厂")
class AgentModelFactoryTest {

    private final AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
    private final IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);
    private final AgentModelFactory factory = new AgentModelFactory(mapper, modelConfigService);

    private static AiModelConfig config(Long id, String code, String baseUrl, String status) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setModelCode(code);
        config.setBaseUrl(baseUrl);
        config.setProvider(null);
        config.setStatus(status);
        return config;
    }

    private static AiExpertAgent definition() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setAgentCode("a1");
        agent.setModelId(1L);
        return agent;
    }

    @Test
    @DisplayName("槽位未配置或与主模型相同时返回空")
    void shouldSkipSlotWhenNotConfiguredOrSameAsMain() {
        AiExpertAgent def = definition();
        def.setSummaryModelId(null);
        assertNull(factory.resolveSummaryModel(def));
        def.setSummaryModelId(1L);
        assertNull(factory.resolveSummaryModel(def));
    }

    @Test
    @DisplayName("槽位模型不可用时回退空")
    void shouldReturnNullWhenSlotConfigUnusable() {
        AiExpertAgent def = definition();
        def.setMemoryModelId(2L);
        when(mapper.selectById(2L)).thenReturn(null);
        assertNull(factory.resolveMemoryModel(def));

        when(mapper.selectById(2L)).thenReturn(config(2L, " ", "https://x", "0"));
        assertNull(factory.resolveMemoryModel(def));

        when(mapper.selectById(2L)).thenReturn(config(2L, "m", "https://x/{id}", "0"));
        assertNull(factory.resolveMemoryModel(def));
    }

    @Test
    @DisplayName("槽位模型可用时构造实例")
    void shouldBuildModelForUsableSlot() {
        AiExpertAgent def = definition();
        def.setSummaryModelId(2L);
        when(mapper.selectById(2L)).thenReturn(config(2L, "gpt-4", "https://api.example.com/v1", "0"));
        when(modelConfigService.decryptApiKey(2L)).thenReturn("sk-x");
        Model model = factory.resolveSummaryModel(def);
        assertNotNull(model);
    }

    @Test
    @DisplayName("还原子智能体模型标识")
    void shouldResolveSubagentModel() {
        assertNull(factory.resolveSubagentModel(null));
        assertNull(factory.resolveSubagentModel(" "));
        assertNull(factory.resolveSubagentModel("other:1"));
        assertNull(factory.resolveSubagentModel(AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX + "abc"));

        String token = AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX + "5";
        when(mapper.selectById(5L)).thenReturn(null);
        assertNull(factory.resolveSubagentModel(token));

        when(mapper.selectById(5L)).thenReturn(config(5L, "gpt", "https://api.example.com", "1"));
        assertNull(factory.resolveSubagentModel(token));

        when(mapper.selectById(5L)).thenReturn(config(5L, "gpt", "https://api.example.com", "0"));
        when(modelConfigService.decryptApiKey(5L)).thenReturn("sk");
        assertNotNull(factory.resolveSubagentModel(token));
    }

    @Test
    @DisplayName("取槽位模型标识")
    void shouldResolveSlotModelCode() {
        assertNull(factory.slotModelCode(null));
        when(mapper.selectById(8L)).thenReturn(null);
        assertNull(factory.slotModelCode(8L));
        AiModelConfig cfg = config(8L, "gpt-8", "https://x", "0");
        when(mapper.selectById(8L)).thenReturn(cfg);
        assertEquals("gpt-8", factory.slotModelCode(8L));
    }

    @Test
    @DisplayName("校验对话模型配置并抛出明确异常")
    void shouldValidateChatModel() {
        assertThrows(ServiceException.class, () -> factory.resolveChatModel(null));
        when(mapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> factory.resolveChatModel(1L));

        AiModelConfig blankCode = config(1L, " ", "https://x", "0");
        when(mapper.selectById(1L)).thenReturn(blankCode);
        assertThrows(ServiceException.class, () -> factory.resolveChatModel(1L));

        AiModelConfig badUrl = config(1L, "m", "https://x/{id}", "0");
        when(mapper.selectById(1L)).thenReturn(badUrl);
        assertThrows(ServiceException.class, () -> factory.resolveChatModel(1L));

        AiModelConfig ok = config(1L, "m", "https://api.example.com/v1", "0");
        when(mapper.selectById(1L)).thenReturn(ok);
        assertSame(ok, factory.resolveChatModel(1L));
    }

    @Test
    @DisplayName("构造模型：无提供商直连 OpenAI 兼容客户端")
    void shouldBuildChatModelWithoutProvider() {
        AiModelConfig cfg = config(9L, "gpt-4", "https://api.example.com/v1/chat/completions", "0");
        cfg.setTimeoutSeconds(30);
        cfg.setMaxRetries(2);
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk");
        assertNotNull(factory.buildChatModel(cfg));
    }

    @Test
    @DisplayName("构造模型：提供商无扩展时回退显式客户端")
    void shouldFallbackWhenProviderUnknown() {
        AiModelConfig cfg = config(9L, "gpt-4", "https://api.example.com/v1", "0");
        cfg.setProvider("no-such-provider");
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk");
        assertNotNull(factory.buildChatModel(cfg));
    }

    @Test
    @DisplayName("构造模型：密钥为空时回退显式客户端")
    void shouldFallbackWhenApiKeyBlank() {
        AiModelConfig cfg = config(9L, "gpt-4", "https://api.example.com/v1", "0");
        cfg.setProvider("openai");
        when(modelConfigService.decryptApiKey(9L)).thenReturn(" ");
        assertNotNull(factory.buildChatModel(cfg));
    }
}