package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDetailDTO;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GetUserTool} 单元测试：操作人 / 权限闸门、ID 校验、缺失与详情渲染。
 */
@DisplayName("查看用户详情工具")
class GetUserToolTest {

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetUserTool tool = new GetUserTool(userService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(GetUserTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(userService, never()).selectUserDetailByAi(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("无用户查询权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(GetUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「用户查询」权限"));
    }

    @Test
    @DisplayName("用户 ID 缺失时校验失败")
    void shouldRejectWhenUserIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result = tool.callAsync(param(GetUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("用户 ID 不能为空"));
    }

    @Test
    @DisplayName("用户不存在时给出未找到文案")
    void shouldHandleMissingUser() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(userService.selectUserDetailByAi(7L, 100L)).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(GetUserTool.TOOL_NAME, Map.of("user_id", "7"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未找到用户"));
    }

    @Test
    @DisplayName("查询成功时渲染用户详情")
    void shouldRenderUserDetail() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        UserDetailDTO dto = new UserDetailDTO();
        dto.setUserId(7L);
        dto.setUserName("zhangsan");
        dto.setNickName("张三");
        dto.setDeptName("研发部");
        dto.setDeptId(3L);
        dto.setGender("0");
        dto.setStatus("0");
        dto.setRoleNames(List.of("管理员", "财务"));
        when(userService.selectUserDetailByAi(7L, 100L)).thenReturn(dto);
        ToolResultBlock result =
            tool.callAsync(param(GetUserTool.TOOL_NAME, Map.of("user_id", "7"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("用户详情："));
        assertTrue(text(result).contains("管理员、财务"));
        assertTrue(text(result).contains("性别：男"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(userService.selectUserDetailByAi(7L, 100L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(GetUserTool.TOOL_NAME, Map.of("user_id", "7"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询用户失败：boom"));
    }
}