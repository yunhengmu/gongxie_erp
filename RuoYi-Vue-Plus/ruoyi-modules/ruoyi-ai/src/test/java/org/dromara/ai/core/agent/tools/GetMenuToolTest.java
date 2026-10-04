package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.domain.MenuDetailDTO;
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
import static org.mockito.Mockito.when;

/**
 * {@link GetMenuTool} 单元测试：操作人 / 权限闸门、ID 校验与详情渲染。
 */
@DisplayName("查看菜单权限详情工具")
class GetMenuToolTest {

    private final MenuService menuService = mock(MenuService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetMenuTool tool = new GetMenuTool(menuService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static MenuDetailDTO menu() {
        MenuDetailDTO dto = new MenuDetailDTO();
        dto.setMenuId(101L);
        dto.setMenuName("角色管理");
        dto.setParentId(1L);
        dto.setMenuType("C");
        dto.setPath("role");
        dto.setComponent("system/role/index");
        dto.setVisible("0");
        dto.setPerms("system:role:list");
        dto.setStatus("0");
        dto.setRemark("角色管理菜单");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "101"))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
    }

    @Test
    @DisplayName("无菜单查询权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "101"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「菜单查询」权限"));
    }

    @Test
    @DisplayName("菜单 ID 非法时校验失败")
    void shouldRejectWhenIdInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "x"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("菜单 ID 不能为空且必须是数字"));
    }

    @Test
    @DisplayName("命中菜单时渲染路由与显示状态")
    void shouldRenderMenuDetail() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.selectMenuDetailByAi(101L)).thenReturn(menu());
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "101"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("类型：菜单"));
        assertTrue(text(result).contains("路由地址：role"));
        assertTrue(text(result).contains("显示状态：显示"));
        assertTrue(text(result).contains("权限标识：system:role:list"));
    }

    @Test
    @DisplayName("按钮类型不渲染路由信息")
    void shouldHideRouteForButton() {
        MenuDetailDTO button = menu();
        button.setMenuType("F");
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.selectMenuDetailByAi(101L)).thenReturn(button);
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "101"), "100")).block();
        assertFalse(isError(result));
        assertFalse(text(result).contains("路由地址"));
    }

    @Test
    @DisplayName("菜单不存在时给出提示")
    void shouldHandleMissingMenu() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.selectMenuDetailByAi(9L)).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "9"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未找到菜单权限"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(menuService.selectMenuDetailByAi(101L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(GetMenuTool.TOOL_NAME, Map.of("menu_id", "101"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询菜单权限失败：boom"));
    }
}