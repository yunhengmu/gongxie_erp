package org.dromara.ai.core.agent;

import org.dromara.ai.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link PromptHardeningMiddleware} 单元测试：system prompt 边界声明的开关口径。
 */
@DisplayName("提示词注入防护中间件")
class PromptHardeningMiddlewareTest {

    private final AiProperties aiProperties = new AiProperties();

    private final PromptHardeningMiddleware middleware = new PromptHardeningMiddleware(aiProperties);

    private String prompt(String base) {
        Mono<String> result = middleware.onSystemPrompt(mock(io.agentscope.core.agent.Agent.class),
            mock(io.agentscope.core.agent.RuntimeContext.class), base);
        return result.block();
    }

    @Test
    @DisplayName("开启时追加边界声明")
    void shouldAppendSectionWhenEnabled() {
        aiProperties.getSecurity().setPromptHardening(true);

        String result = prompt("原提示词");

        assertTrue(result.startsWith("原提示词"));
        assertTrue(result.contains("外部内容安全边界"));
        assertTrue(result.contains("<untrusted>"));
    }

    @Test
    @DisplayName("关闭时原样返回")
    void shouldReturnBaseWhenDisabled() {
        aiProperties.getSecurity().setPromptHardening(false);

        assertFalse(prompt("原提示词").contains("外部内容安全边界"));
    }

}