package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.domain.RoleDetailDTO;
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
 * {@link GetRoleTool} 单元测试：操作人 / 权限闸门、ID 校验与详情渲染。
 */
@DisplayName("查看角色详情工具")
class GetRoleToolTest {

    private final RoleService roleService = mock(RoleService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetRoleTool tool = new GetRoleTool(roleService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static RoleDetailDTO role() {
        RoleDetailDTO dto = new RoleDetailDTO();
        dto.setRoleId(2L);
        dto.setRoleName("普通角色");
        dto.setRoleKey("common");
        dto.setRoleSort(2);
        dto.setDataScope("1");
        dto.setStatus("1");
        dto.setRemark("默认角色");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result =
            tool.callAsync(param(GetRoleTool.TOOL_NAME, Map.of("role_id", "2"))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
    }

    @Test
    @DisplayName("无角色查询权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result =
            tool.callAsync(param(GetRoleTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「角色查询」权限"));
    }

    @Test
    @DisplayName("角色 ID 非法时校验失败")
    void shouldRejectWhenIdInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(GetRoleTool.TOOL_NAME, Map.of("role_id", "abc"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("角色 ID 不能为空且必须是数字"));
    }

    @Test
    @DisplayName("命中时渲染角色详情")
    void shouldRenderDetail() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(2L)).thenReturn(role());
        ToolResultBlock result =
            tool.callAsync(param(GetRoleTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("权限字符：common"));
        assertTrue(text(result).contains("数据范围：全部数据权限"));
        assertTrue(text(result).contains("状态：停用"));
    }

    @Test
    @DisplayName("角色不存在时给出提示")
    void shouldHandleMissingRole() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(9L)).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(GetRoleTool.TOOL_NAME, Map.of("role_id", "9"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未找到角色"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(roleService.selectRoleDetailByAi(2L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(GetRoleTool.TOOL_NAME, Map.of("role_id", "2"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询角色失败：boom"));
    }
}