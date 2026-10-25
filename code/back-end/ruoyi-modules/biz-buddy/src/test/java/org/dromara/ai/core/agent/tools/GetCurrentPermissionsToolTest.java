package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GetCurrentPermissionsTool} 单元测试：身份缺失、通配权限、空集合与异常包装。
 */
@DisplayName("获取当前用户权限工具")
class GetCurrentPermissionsToolTest {

    private static final long SUPER_ADMIN_ID = 1761100000000000001L;

    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetCurrentPermissionsTool tool = new GetCurrentPermissionsTool(permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别提问用户身份时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(GetCurrentPermissionsTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前提问用户的平台身份"));
        verify(permissionService, never()).getMenuPermission(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("权限为空时给出无权限文案")
    void shouldHandleEmptyPermissions() {
        when(permissionService.getRolePermission(100L)).thenReturn(null);
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(GetCurrentPermissionsTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("角色标识（0 个）：（无）"));
        assertTrue(text(result).contains("菜单权限（0 个）：（无）"));
    }

    @Test
    @DisplayName("含通配权限时给出说明")
    void shouldExplainWildcardPermission() {
        when(permissionService.getRolePermission(100L)).thenReturn(Set.of("admin"));
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*", "system:user:list"));
        ToolResultBlock result = tool.callAsync(param(GetCurrentPermissionsTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("角色标识（1 个）：admin"));
        assertTrue(text(result).contains("通配权限"));
    }

    @Test
    @DisplayName("超级管理员标识正确")
    void shouldMarkSuperAdmin() {
        when(permissionService.getRolePermission(SUPER_ADMIN_ID)).thenReturn(Set.of());
        when(permissionService.getMenuPermission(SUPER_ADMIN_ID)).thenReturn(Set.of());
        ToolResultBlock result =
            tool.callAsync(param(GetCurrentPermissionsTool.TOOL_NAME, Map.of(), String.valueOf(SUPER_ADMIN_ID))).block();
        assertTrue(text(result).contains("是否超级管理员：是（拥有平台全部权限）"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getRolePermission(100L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(GetCurrentPermissionsTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询当前用户权限失败：boom"));
    }
}