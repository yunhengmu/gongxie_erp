package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiTrustApplier} 的纯单元测试（不碰运行时 / 数据库）。
 *
 * <p>覆盖：入参与槽位键为空时的整体短路，以及入口 / 专家两条链路各自取对应装配的权限上下文基线
 * 并落到正确槽位（静态支撑方法的调用参数逐项断言）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户级信任落位")
class AiTrustApplierTest {

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private IAiAgentService agentService;

    @Mock
    private HarnessAgent agent;

    @Mock
    private AiAgent definition;

    private AiTrustApplier trustApplier;

    @BeforeEach
    void setUp() {
        trustApplier = new AiTrustApplier(agentRegistry, agentService);
    }

    @Test
    @DisplayName("agent 为空：整体跳过且不触达依赖")
    void shouldSkipWhenAgentNull() {
        trustApplier.apply(null, definition, null, 1L, 2L);

        verifyNoInteractions(agentRegistry, agentService);
    }

    @Test
    @DisplayName("会话主键为空：整体跳过")
    void shouldSkipWhenSessionIdNull() {
        trustApplier.apply(agent, definition, null, 1L, null);

        verifyNoInteractions(agentRegistry, agentService);
    }

    @Test
    @DisplayName("入口链路：以入口装配权限上下文为基线并入用户会话槽位")
    void shouldApplyEntryTrust() {
        PermissionContextState context = PermissionContextState.builder().build();
        when(definition.getId()).thenReturn(7L);
        when(agentService.trustedToolCodes(7L, 1L)).thenReturn(Set.of("create_notice"));
        when(agentRegistry.permissionContextOf(definition, (AiSessionResourceSet) null)).thenReturn(context);

        try (MockedStatic<AiPermissionSupport> mocked = mockStatic(AiPermissionSupport.class)) {
            trustApplier.apply(agent, definition, null, 1L, 2L);

            mocked.verify(() -> AiPermissionSupport.applyUserToolTrust(
                agent, context, Set.of("create_notice"), "1", "2"));
        }
    }

    @Test
    @DisplayName("专家链路：以专家装配权限上下文为基线并入专家会话槽位")
    void shouldApplyExpertTrust() {
        PermissionContextState context = PermissionContextState.builder().build();
        when(definition.getId()).thenReturn(7L);
        when(agentService.trustedToolCodes(7L, 1L)).thenReturn(Set.of());
        when(agentRegistry.permissionContextOfExpert(definition, (AiSessionResourceSet) null)).thenReturn(context);

        try (MockedStatic<AiPermissionSupport> mocked = mockStatic(AiPermissionSupport.class)) {
            trustApplier.applyExpert(agent, definition, null, 1L, "9#8");

            mocked.verify(() -> AiPermissionSupport.applyUserToolTrust(agent, context, Set.of(), "1", "9#8"));
        }
    }

    @Test
    @DisplayName("专家槽位键为空：整体跳过")
    void shouldSkipWhenExpertSessionIdBlank() {
        trustApplier.applyExpert(agent, definition, null, 1L, "  ");

        verifyNoInteractions(agentRegistry, agentService);
    }
}
