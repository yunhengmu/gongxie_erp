package org.dromara.ai.core.agent;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyAction;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolResultGuardTool} 单元测试：结果脱敏（模型侧副本）、阻断替换、失败/暂停放行、元信息透传。
 */
@DisplayName("工具结果脱敏装饰器")
class ToolResultGuardToolTest {

    private static final String PLAIN = "用户：张三，手机 15812348888，邮箱 zybizbuddy@163.com";

    private final AgentTool delegate = delegate();
    private final AiSafetyGuard safetyGuard = mock(AiSafetyGuard.class);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    /**
     * 构造已打桩元信息的被装饰工具（装饰器构造期会读取 name / description / parameters 等）。
     */
    private static AgentTool delegate() {
        AgentTool agentTool = mock(AgentTool.class);
        when(agentTool.getName()).thenReturn("get_user");
        when(agentTool.getDescription()).thenReturn("desc");
        when(agentTool.getParameters()).thenReturn(Map.of("type", "object"));
        return agentTool;
    }

    private ToolCallParam param() {
        return ToolCallParam.builder()
            .toolUseBlock(new ToolUseBlock("call-1", "get_user", Map.of()))
            .input(Map.of())
            .build();
    }

    private ToolResultBlock call(ToolResultBlock stubbed) {
        when(delegate.callAsync(any(ToolCallParam.class))).thenReturn(Mono.just(stubbed));
        return new ToolResultGuardTool(delegate, safetyGuard).callAsync(param()).block();
    }

    @Test
    @DisplayName("安全能力全关时走快速路径，不改写也不调守卫")
    void shouldSkipWhenNoSafetyActive() {
        when(safetyGuard.anyActive()).thenReturn(false);
        ToolResultBlock original = ToolResultBlock.text(PLAIN);

        ToolResultBlock result = call(original);

        assertSame(original, result);
        verify(safetyGuard, never()).guard(anyString(), any());
    }

    @Test
    @DisplayName("命中 PII 时以脱敏文本回填（喂给模型的那一份也脱敏）")
    void shouldMaskWhenPiiHit() {
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard(eq(PLAIN), eq(SafetyScene.TOOL_RESULT))).thenReturn(new SafetyDecision(
            SafetyAction.MASK,
            "用户：张三，手机 158****8888，邮箱 z***@163.com", true, List.of("MOBILE", "EMAIL")));

        ToolResultBlock result = call(ToolResultBlock.text(PLAIN));

        assertEquals("用户：张三，手机 158****8888，邮箱 z***@163.com", text(result));
        assertFalse(text(result).contains("15812348888"), "原文不应出现在模型可见结果里");
    }

    @Test
    @DisplayName("未命中时返回原对象")
    void shouldKeepOriginalWhenNotHit() {
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard(anyString(), eq(SafetyScene.TOOL_RESULT)))
            .thenReturn(SafetyDecision.pass(PLAIN));
        ToolResultBlock original = ToolResultBlock.text(PLAIN);

        assertSame(original, call(original));
    }

    @Test
    @DisplayName("内容审核判定阻断时以拒答文案替代原文（不把命中词送给模型）")
    void shouldReplaceWithRefusalWhenBlocked() {
        when(safetyGuard.anyActive()).thenReturn(true);
        when(safetyGuard.guard(anyString(), eq(SafetyScene.TOOL_RESULT)))
            .thenReturn(SafetyDecision.block(List.of("某敏感词")));

        ToolResultBlock result = call(ToolResultBlock.text("含敏感词的公告正文"));

        assertEquals(AiSafetyGuard.REFUSAL_TEXT, text(result));
        assertFalse(text(result).contains("敏感词"), "命中词不应回填给模型");
    }

    @Test
    @DisplayName("失败结果原样放行（错误提示属业务校验文案，不改写语义）")
    void shouldPassThroughErrorResult() {
        when(safetyGuard.anyActive()).thenReturn(true);

        ToolResultBlock result = call(ToolResultBlock.error("user not found"));

        assertTrue(ToolTestSupport.isError(result));
        verify(safetyGuard, never()).guard(anyString(), any());
    }

    @Test
    @DisplayName("HITL 暂停结果原样放行（保住 agentscope_suspended 元数据）")
    void shouldPassThroughSuspendedResult() {
        when(safetyGuard.anyActive()).thenReturn(true);
        ToolResultBlock original = ToolResultBlock.suspended(
            new ToolUseBlock("call-1", "create_notice", Map.of()));

        ToolResultBlock result = call(original);

        assertSame(original, result);
        assertTrue(result.isSuspended());
        verify(safetyGuard, never()).guard(anyString(), any());
    }

    @Test
    @DisplayName("装饰器保留原工具元信息（只读标记与名称不得被改写）")
    void shouldPreserveToolMetadata() {
        AgentTool readOnlyDelegate = mock(AgentTool.class);
        when(readOnlyDelegate.getName()).thenReturn("get_user");
        when(readOnlyDelegate.getDescription()).thenReturn("desc");
        when(readOnlyDelegate.getParameters()).thenReturn(Map.of("type", "object"));
        when(readOnlyDelegate.isReadOnly()).thenReturn(true);

        ToolResultGuardTool tool = new ToolResultGuardTool(readOnlyDelegate, safetyGuard);

        assertEquals("get_user", tool.getName());
        assertEquals("desc", tool.getDescription());
        assertTrue(tool.isReadOnly(), "只读标记必须透传，否则只读工具会被误判为需人工确认");
    }
}
