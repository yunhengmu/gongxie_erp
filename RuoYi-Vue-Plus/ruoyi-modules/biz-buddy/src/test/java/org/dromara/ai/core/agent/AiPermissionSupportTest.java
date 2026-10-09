package org.dromara.ai.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiPermissionDecisionLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiPermissionSupport} 单元测试：ask 规则改写、记住信任合并与来源筛选。
 */
@DisplayName("权限上下文改写支撑")
class AiPermissionSupportTest {

    private final HarnessAgent agent = mock(HarnessAgent.class);
    private final ReActAgent delegate = mock(ReActAgent.class);

    private void bindState(PermissionContextState context) {
        AgentState agentState = AgentState.builder().permissionContext(context).build();
        when(agent.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState("u", "s")).thenReturn(agentState);
    }

    private static PermissionRule rule(String tool, PermissionBehavior behavior, String source) {
        return new PermissionRule(tool, null, behavior, source);
    }

    @Test
    @DisplayName("记忆来源的放行工具集合筛选")
    void shouldCollectRememberAllowToolCodes() {
        assertTrue(AiPermissionSupport.rememberAllowToolCodes(null).isEmpty());
        PermissionContextState context = PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addAllowRule("remembered", rule("remembered", PermissionBehavior.ALLOW,
                AiPermissionDecisionLog.SOURCE_REMEMBER))
            .addAllowRule("normal", rule("normal", PermissionBehavior.ALLOW, "config"))
            .build();
        Set<String> codes = AiPermissionSupport.rememberAllowToolCodes(context);
        assertEquals(Set.of("remembered"), codes);
    }

    @Test
    @DisplayName("把 ask 规则改写为 deny 并固定为 DONT_ASK")
    void shouldDenyAskingTools() {
        PermissionContextState base = PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addAllowRule("toolB", rule("toolB", PermissionBehavior.ALLOW, "config"))
            .addAskRule("toolA", rule("toolA", PermissionBehavior.ASK, "config"))
            .build();
        bindState(base);

        AiPermissionSupport.denyAskingTools(agent, "u", "s");

        ArgumentCaptor<PermissionContextState> captor = ArgumentCaptor.forClass(PermissionContextState.class);
        verify(delegate).replacePermissionContext(eq("u"), eq("s"), captor.capture());
        PermissionContextState replaced = captor.getValue();
        assertEquals(PermissionMode.DONT_ASK, replaced.getMode());
        assertTrue(replaced.getAskRules().isEmpty());
        assertTrue(replaced.getDenyRules().containsKey("toolA"));
        assertTrue(replaced.getAllowRules().containsKey("toolB"));
    }

    @Test
    @DisplayName("信任集合一致时跳过重建")
    void shouldSkipTrustRebuildWhenUnchanged() {
        PermissionContextState current = PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addAllowRule("toolA", rule("toolA", PermissionBehavior.ALLOW,
                AiPermissionDecisionLog.SOURCE_REMEMBER))
            .build();
        bindState(current);

        AiPermissionSupport.applyUserToolTrust(agent, PermissionContextState.builder().build(),
            Set.of("toolA"), "u", "s");
        verify(delegate, never()).replacePermissionContext(any(), any(), any());
    }

    @Test
    @DisplayName("按基线重建并注入记住信任规则")
    void shouldRebuildTrustContext() {
        PermissionContextState current = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
        bindState(current);
        PermissionContextState base = PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addAllowRule("toolZ", rule("toolZ", PermissionBehavior.ALLOW, "config"))
            .addAskRule("toolY", rule("toolY", PermissionBehavior.ASK, "config"))
            .build();

        AiPermissionSupport.applyUserToolTrust(agent, base, Set.of("toolX"), "u", "s");

        ArgumentCaptor<PermissionContextState> captor = ArgumentCaptor.forClass(PermissionContextState.class);
        verify(delegate).replacePermissionContext(eq("u"), eq("s"), captor.capture());
        PermissionContextState rebuilt = captor.getValue();
        assertEquals(PermissionMode.DEFAULT, rebuilt.getMode());
        // 基线 ask 规则保留（本次未记住）
        assertTrue(rebuilt.getAskRules().containsKey("toolY"));
        assertTrue(rebuilt.getAllowRules().containsKey("toolZ"));
        // 记住的工具以 remember 来源放行
        Set<String> remembered = AiPermissionSupport.rememberAllowToolCodes(rebuilt);
        assertTrue(remembered.contains("toolX"));
        assertFalse(rebuilt.getAskRules().containsKey("toolX"));
    }

    @Test
    @DisplayName("已记住的工具不再保留同名 ask 规则")
    void shouldDropAskRuleForTrustedTool() {
        PermissionContextState current = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
        bindState(current);
        PermissionContextState base = PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addAskRule("toolY", rule("toolY", PermissionBehavior.ASK, "config"))
            .build();

        AiPermissionSupport.applyUserToolTrust(agent, base, Set.of("toolY"), "u", "s");

        ArgumentCaptor<PermissionContextState> captor = ArgumentCaptor.forClass(PermissionContextState.class);
        verify(delegate).replacePermissionContext(eq("u"), eq("s"), captor.capture());
        assertFalse(captor.getValue().getAskRules().containsKey("toolY"));
    }
}