package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SearchRolesTool} 单元测试：操作人 / 权限闸门、状态校验与结果渲染。
 */
@DisplayName("搜索角色工具")
class SearchRolesToolTest {

    private final RoleService roleService = mock(RoleService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SearchRolesTool tool = new SearchRolesTool(roleService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static RoleDetailDTO role() {
        RoleDetailDTO dto = new RoleDetailDTO();
        dto.setRoleId(2L);
        dto.setRoleName("普通角色");
        dto.setRoleKey("common");
        dto.setDataScope("4");
        dto.setStatus("0");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(SearchRolesTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(roleService, never()).searchRolesByAi(any(), any());
    }

    @Test
    @DisplayName("无角色列表权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(SearchRolesTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「角色列表」权限"));
    }

    @Test
    @DisplayName("状态非法时校验失败")
    void shouldRejectWhenStatusInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchRolesTool.TOOL_NAME, Map.of("status", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("状态只能是 0（正常）或 1（停用）"));
    }

    @Test
    @DisplayName("搜索命中时渲染角色清单")
    void shouldRenderMatches() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.searchRolesByAi(any(), any())).thenReturn(List.of(role()));
        ToolResultBlock result = tool.callAsync(param(SearchRolesTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("共 1 个角色"));
        assertTrue(text(result).contains("role_id=2"));
        assertTrue(text(result).contains("权限字符=common"));
        assertTrue(text(result).contains("本部门及以下数据权限"));
    }

    @Test
    @DisplayName("搜索无命中时给出空结果文案")
    void shouldHandleEmptyResult() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.searchRolesByAi(any(), any())).thenReturn(List.of());
        ToolResultBlock result = tool.callAsync(param(SearchRolesTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(text(result).contains("没有匹配的角色"));
    }

    @Test
    @DisplayName("查询异常时包装为搜索失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.searchRolesByAi(any(), any())).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(SearchRolesTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("搜索角色失败：boom"));
    }
}