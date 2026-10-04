package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.domain.RoleDetailDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GetRolePermissionsTool} 单元测试：操作人 / 权限闸门、ID 校验与权限清单渲染。
 */
@DisplayName("查询角色权限工具")
class GetRolePermissionsToolTest {

    private final RoleService roleService = mock(RoleService.class);
    private final MenuService menuService = mock(MenuService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetRolePermissionsTool tool =
        new GetRolePermissionsTool(roleService, menuService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static RoleDetailDTO role() {
        RoleDetailDTO dto = new RoleDetailDTO();
        dto.setRoleId(2L);
        dto.setRoleName("普通角色");
        dto.setRoleKey("common");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", "2"))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
    }

    @Test
    @DisplayName("无角色查询权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「角色查询」权限"));
    }

    @Test
    @DisplayName("角色 ID 非法时校验失败")
    void shouldRejectWhenIdInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", ""), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("角色 ID 不能为空且必须是数字"));
    }

    @Test
    @DisplayName("命中时渲染角色与权限清单")
    void shouldRenderPermissions() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(2L)).thenReturn(role());
        when(menuService.selectRolePermsByAi(2L)).thenReturn(List.of("system:role:list", "system:user:list"));
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("普通角色"));
        assertTrue(text(result).contains("共分配 2 个权限标识"));
        assertTrue(text(result).contains("system:role:list"));
    }

    @Test
    @DisplayName("角色无权限时给出空清单文案")
    void shouldHandleEmptyPermissions() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(2L)).thenReturn(role());
        when(menuService.selectRolePermsByAi(2L)).thenReturn(List.of());
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("尚未分配任何权限标识"));
    }

    @Test
    @DisplayName("角色不存在时给出提示")
    void shouldHandleMissingRole() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(9L)).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", "9"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未找到角色"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(2L)).thenReturn(role());
        when(menuService.selectRolePermsByAi(2L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(GetRolePermissionsTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询角色权限失败：boom"));
    }
}