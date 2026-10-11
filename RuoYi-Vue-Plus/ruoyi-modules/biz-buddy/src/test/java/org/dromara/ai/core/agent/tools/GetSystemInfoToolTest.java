package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.core.agent.ToolTestSupport;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GetSystemInfoTool} 单元测试：渲染主机 / JVM 信息。
 */
@DisplayName("获取系统信息工具")
class GetSystemInfoToolTest {

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无参调用返回操作系统、处理器核数、内存、Java 版本与时区")
    void shouldRenderSystemInfo() {
        GetSystemInfoTool tool = new GetSystemInfoTool();

        ToolResultBlock result = tool.callAsync(param(GetSystemInfoTool.TOOL_NAME, Map.of(), "100")).block();

        assertNotNull(result);
        assertFalse(ToolTestSupport.isError(result));
        String output = text(result);
        assertTrue(output.contains("操作系统："));
        assertTrue(output.contains("处理器逻辑核数："));
        assertTrue(output.contains("JVM 堆内存："));
        assertTrue(output.contains("Java 版本："));
        assertTrue(output.contains("时区："));
    }

}