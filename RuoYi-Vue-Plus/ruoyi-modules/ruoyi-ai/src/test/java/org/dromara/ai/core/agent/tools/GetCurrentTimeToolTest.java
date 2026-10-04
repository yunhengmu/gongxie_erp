package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.ToolTestSupport;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GetCurrentTimeTool} 单元测试：按配置时区渲染当前时间。
 */
@DisplayName("获取当前时间工具")
class GetCurrentTimeToolTest {

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("默认时区下返回当前时间")
    void shouldRenderDefaultZone() {
        GetCurrentTimeTool tool = new GetCurrentTimeTool(new AiProperties());
        ToolResultBlock result = tool.callAsync(param(GetCurrentTimeTool.TOOL_NAME, Map.of(), "100")).block();
        assertNotNull(result);
        assertFalse(ToolTestSupport.isError(result));
        assertTrue(text(result).contains("当前时间："));
        assertTrue(text(result).contains("时区 Asia/Shanghai"));
    }

    @Test
    @DisplayName("自定义时区生效")
    void shouldRespectConfiguredZone() {
        AiProperties properties = new AiProperties();
        properties.getChat().setCurrentTimeZone("UTC");
        GetCurrentTimeTool tool = new GetCurrentTimeTool(properties);
        ToolResultBlock result = tool.callAsync(param(GetCurrentTimeTool.TOOL_NAME, Map.of())).block();
        assertTrue(text(result).contains("时区 UTC"));
    }

    @Test
    @DisplayName("非法时区回退 JVM 默认时区")
    void shouldFallbackWhenZoneInvalid() {
        AiProperties properties = new AiProperties();
        properties.getChat().setCurrentTimeZone("Not/AZone");
        GetCurrentTimeTool tool = new GetCurrentTimeTool(properties);
        ToolResultBlock result = tool.callAsync(param(GetCurrentTimeTool.TOOL_NAME, Map.of())).block();
        assertTrue(text(result).contains("当前时间："));
        assertTrue(text(result).contains("时区"));
    }
}