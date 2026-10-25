package org.dromara.ai.core.safety;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExternalApiSafetyEngine} 单元测试：可用性判定、响应解析与缺配置兜底。
 *
 * <p>不覆盖真实 HTTP 调用与响应体读取（{@code HttpUtil} / {@code JsonUtils} 均为静态调用、无法注入替身）：
 * 网络路径由 e2e 接口验证覆盖，此处只测已解析响应节点的映射与配置判定。</p>
 */
@DisplayName("外部内容安全引擎")
class ExternalApiSafetyEngineTest {

    private final ExternalApiSafetyEngine engine = new ExternalApiSafetyEngine(new AiProperties());

    private static AiSecurityConfigBo config(String endpoint, String apiKey) {
        AiSecurityConfigBo cfg = new AiSecurityConfigBo();
        cfg.setProviderEndpoint(endpoint);
        cfg.setProviderApiKey(apiKey);
        return cfg;
    }

    @Test
    @DisplayName("端点与密钥齐备才可用（缺一即降级本地词库）")
    void shouldRequireEndpointAndKey() {
        assertTrue(engine.available(config("https://ext.example.com/moderate", "k")));
        assertFalse(engine.available(config("", "k")));
        assertFalse(engine.available(config("https://ext.example.com/moderate", "")));
        assertFalse(engine.available(null));
    }

    /**
     * 用独立 JsonMapper 把 JSON 文本读成节点（不依赖全局 {@code JsonUtils} 的 Spring 上下文）。
     *
     * @param json JSON 文本
     * @return 根节点
     */
    private static tools.jackson.databind.JsonNode node(String json) {
        return JsonMapper.builder().build().readTree(json);
    }

    @Test
    @DisplayName("响应命中时返回命中项与类别")
    void shouldParseHit() {
        ContentSafetyResult result = ExternalApiSafetyEngine.parseResult(
            node("{\"hit\":true,\"hits\":[\"违规词\",\"违禁词\"],\"categories\":[\"违禁\"]}"));

        assertTrue(result.hit());
        assertEquals(2, result.hits().size());
        assertEquals("违禁", result.categories().get(0));
    }

    @Test
    @DisplayName("未命中 / 字段缺失 / 空节点一律按未命中")
    void shouldMissOnAbsentOrMalformed() {
        assertFalse(ExternalApiSafetyEngine.parseResult(node("{\"hit\":false}")).hit());
        assertFalse(ExternalApiSafetyEngine.parseResult(node("{}")).hit());
        assertFalse(ExternalApiSafetyEngine.parseResult(node("null")).hit());
        assertFalse(ExternalApiSafetyEngine.parseResult((tools.jackson.databind.JsonNode) null).hit());
    }

    @Test
    @DisplayName("hits / categories 缺失或非数组时命中项为空（不抛异常）")
    void shouldTolerateNonArrayFields() {
        ContentSafetyResult noHits = ExternalApiSafetyEngine.parseResult(node("{\"hit\":true}"));
        assertTrue(noHits.hit());
        assertTrue(noHits.hits().isEmpty());

        ContentSafetyResult badType = ExternalApiSafetyEngine.parseResult(
            node("{\"hit\":true,\"hits\":\"不是数组\",\"categories\":1}"));
        assertTrue(badType.hit());
        assertTrue(badType.hits().isEmpty());
        assertTrue(badType.categories().isEmpty());
    }

    @Test
    @DisplayName("配置不全时直接调用按未命中放行（不发起外呼）")
    void shouldFailOpenWhenConfigMissing() {
        assertFalse(engine.check("联系电话 13812345678", SafetyScene.OUTPUT, null).hit());
        assertFalse(engine.check("联系电话 13812345678", SafetyScene.OUTPUT, config("", "")).hit());
        assertFalse(engine.check("", SafetyScene.OUTPUT, config("https://x", "k")).hit());
    }

}
