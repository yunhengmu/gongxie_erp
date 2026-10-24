package org.dromara.ai.core.agent;

import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PromptHardeningMiddleware} 单元测试：system prompt 边界声明的开关口径。
 *
 * <p>开关取自<b>运行期配置</b>（{@link AiRuntimeConfigStore#security()}），而非静态 yml，
 * 故此处以打桩的运行期配置中心驱动两种取值。</p>
 */
@DisplayName("提示词注入防护中间件")
class PromptHardeningMiddlewareTest {

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final PromptHardeningMiddleware middleware = new PromptHardeningMiddleware(runtimeConfigStore);

    private void stubPromptHardening(boolean value) {
        AiSecurityConfigBo security = new AiSecurityConfigBo();
        security.setPromptHardening(value);
        when(runtimeConfigStore.security()).thenReturn(security);
    }

    private String prompt(String base) {
        Mono<String> result = middleware.onSystemPrompt(mock(io.agentscope.core.agent.Agent.class),
            mock(io.agentscope.core.agent.RuntimeContext.class), base);
        return result.block();
    }

    @Test
    @DisplayName("运行期配置开启时追加边界声明")
    void shouldAppendSectionWhenEnabled() {
        stubPromptHardening(true);

        String result = prompt("原提示词");

        assertTrue(result.startsWith("原提示词"));
        assertTrue(result.contains("外部内容安全边界"));
        assertTrue(result.contains("<untrusted>"));
    }

    @Test
    @DisplayName("运行期配置关闭时原样返回")
    void shouldReturnBaseWhenDisabled() {
        stubPromptHardening(false);

        assertFalse(prompt("原提示词").contains("外部内容安全边界"));
    }

}
