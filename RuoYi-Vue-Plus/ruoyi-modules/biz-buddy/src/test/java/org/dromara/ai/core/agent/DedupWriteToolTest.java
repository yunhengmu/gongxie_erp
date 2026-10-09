package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DedupWriteTool} 单元测试：未执行放行、重复跳过、仅成功登记、无作用域放行。
 */
@DisplayName("写工具去重装饰器")
class DedupWriteToolTest {

    private static final String SCOPE = "1#2";

    private final AgentTool delegate = delegate();
    private final AiWriteDedupStore dedupStore = mock(AiWriteDedupStore.class);
    private final DedupWriteTool tool = new DedupWriteTool(delegate, dedupStore);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    /**
     * 构造已打桩元信息的被装饰工具（装饰器构造期会读取 name / description / parameters）。
     */
    private static AgentTool delegate() {
        AgentTool agentTool = mock(AgentTool.class);
        when(agentTool.getName()).thenReturn("create_user");
        when(agentTool.getDescription()).thenReturn("desc");
        when(agentTool.getParameters()).thenReturn(Map.of("type", "object"));
        return agentTool;
    }

    private ToolCallParam param(String sessionId, Map<String, Object> input) {
        RuntimeContext context = RuntimeContext.builder().sessionId(sessionId).userId("100").build();
        return ToolCallParam.builder()
            .toolUseBlock(new ToolUseBlock("call-1", "create_user", input))
            .input(input)
            .runtimeContext(context)
            .build();
    }

    @Test
    @DisplayName("未执行过时放行并登记成功结果")
    void shouldExecuteAndRememberWhenNotSeen() {
        Map<String, Object> input = Map.of("user_name", "zhangsan");
        when(dedupStore.seen(SCOPE, "create_user", input)).thenReturn(false);
        when(delegate.callAsync(any(ToolCallParam.class))).thenReturn(Mono.just(ToolResultBlock.text("ok")));

        ToolResultBlock result = tool.callAsync(param(SCOPE, input)).block();

        assertFalse(ToolTestSupport.isError(result));
        assertTrue(text(result).contains("ok"));
        verify(dedupStore).remember(SCOPE, "create_user", input);
    }

    @Test
    @DisplayName("已执行过时跳过且不调用被装饰工具")
    void shouldSkipWhenAlreadySeen() {
        Map<String, Object> input = Map.of("user_name", "zhangsan");
        when(dedupStore.seen(SCOPE, "create_user", input)).thenReturn(true);

        ToolResultBlock result = tool.callAsync(param(SCOPE, input)).block();

        assertTrue(text(result).contains("已跳过重复执行"));
        verify(delegate, never()).callAsync(any(ToolCallParam.class));
    }

    @Test
    @DisplayName("首调用返回错误时不登记，允许重试")
    void shouldNotRememberOnErrorResult() {
        Map<String, Object> input = Map.of("user_name", "zhangsan");
        when(dedupStore.seen(SCOPE, "create_user", input)).thenReturn(false);
        when(delegate.callAsync(any(ToolCallParam.class))).thenReturn(Mono.just(ToolResultBlock.error("bad")));

        ToolResultBlock result = tool.callAsync(param(SCOPE, input)).block();

        assertTrue(ToolTestSupport.isError(result));
        verify(dedupStore, never()).remember(any(), any(), any());
    }

    @Test
    @DisplayName("无专家会话作用域时直接放行")
    void shouldExecuteWhenScopeMissing() {
        Map<String, Object> input = Map.of("user_name", "zhangsan");
        when(delegate.callAsync(any(ToolCallParam.class))).thenReturn(Mono.just(ToolResultBlock.text("ok")));

        ToolResultBlock result = tool.callAsync(param(null, input)).block();

        assertTrue(text(result).contains("ok"));
        verify(dedupStore, never()).seen(any(), any(), any());
    }
}