package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.domain.MenuDetailDTO;
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
 * {@link SearchMenusTool} 单元测试：操作人 / 权限闸门、状态校验与结果渲染。
 */
@DisplayName("搜索菜单权限工具")
class SearchMenusToolTest {

    private final MenuService menuService = mock(MenuService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SearchMenusTool tool = new SearchMenusTool(menuService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static MenuDetailDTO menu() {
        MenuDetailDTO dto = new MenuDetailDTO();
        dto.setMenuId(101L);
        dto.setMenuName("角色查询");
        dto.setParentId(100L);
        dto.setMenuType("F");
        dto.setPerms("system:role:query");
        dto.setStatus("0");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(SearchMenusTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(menuService, never()).searchMenusByAi(any(), any(), any());
    }

    @Test
    @DisplayName("无菜单列表权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(SearchMenusTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「菜单列表」权限"));
    }

    @Test
    @DisplayName("状态非法时校验失败")
    void shouldRejectWhenStatusInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchMenusTool.TOOL_NAME, Map.of("status", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("状态只能是 0（正常）或 1（停用）"));
    }

    @Test
    @DisplayName("搜索命中时渲染菜单清单")
    void shouldRenderMatches() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.searchMenusByAi(any(), any(), any())).thenReturn(List.of(menu()));
        ToolResultBlock result =
            tool.callAsync(param(SearchMenusTool.TOOL_NAME, Map.of("perms", "system:role"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("共 1 个菜单权限"));
        assertTrue(text(result).contains("menu_id=101"));
        assertTrue(text(result).contains("类型=按钮"));
        assertTrue(text(result).contains("权限标识=system:role:query"));
    }

    @Test
    @DisplayName("搜索无命中时给出空结果文案")
    void shouldHandleEmptyResult() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.searchMenusByAi(any(), any(), any())).thenReturn(List.of());
        ToolResultBlock result = tool.callAsync(param(SearchMenusTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(text(result).contains("没有匹配的菜单权限"));
    }

    @Test
    @DisplayName("查询异常时包装为搜索失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.searchMenusByAi(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(SearchMenusTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("搜索菜单权限失败：boom"));
    }
}