package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;

import java.util.Map;

/**
 * 工具测试的公共构造与断言辅助（供 {@code core.agent} 与 {@code core.agent.tools} 两个测试包使用）。
 */
public final class ToolTestSupport {

    private ToolTestSupport() {
    }

    /**
     * 构造运行期上下文（仅携带 userId）。
     */
    public static RuntimeContext context(String userId) {
        return RuntimeContext.builder().userId(userId).build();
    }

    /**
     * 构造携带操作人的工具调用参数。
     */
    public static ToolCallParam param(String toolName, Map<String, Object> input, String userId) {
        return param(toolName, input, context(userId));
    }

    /**
     * 构造无操作人的工具调用参数。
     */
    public static ToolCallParam param(String toolName, Map<String, Object> input) {
        return param(toolName, input, context(null));
    }

    /**
     * 构造工具调用参数（使用给定运行期上下文）。
     */
    public static ToolCallParam param(String toolName, Map<String, Object> input, RuntimeContext context) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        return ToolCallParam.builder()
            .toolUseBlock(new ToolUseBlock("call-1", toolName, safeInput))
            .input(safeInput)
            .runtimeContext(context)
            .build();
    }

    /**
     * 取工具结果的文本（首个内容块）。
     */
    public static String text(ToolResultBlock result) {
        ContentBlock block = result.getOutput().get(0);
        return block instanceof TextBlock textBlock ? textBlock.getText() : String.valueOf(block);
    }

    /**
     * 工具结果是否为错误态。
     */
    public static boolean isError(ToolResultBlock result) {
        return result.getState() == ToolResultState.ERROR;
    }
}