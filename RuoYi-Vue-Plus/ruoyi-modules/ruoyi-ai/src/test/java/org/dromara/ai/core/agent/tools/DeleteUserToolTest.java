package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.UserService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeleteUserTool} 单元测试：操作人 / 权限闸门、ID 校验与结果封装。
 */
@DisplayName("删除用户工具")
class DeleteUserToolTest {

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final DeleteUserTool tool = new DeleteUserTool(userService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(DeleteUserTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(userService, never()).deleteUserByAi(anyLong(), anyLong());
    }

    @Test
    @DisplayName("无用户删除权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:list"));
        ToolResultBlock result = tool.callAsync(param(DeleteUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「用户删除」权限"));
        verify(userService, never()).deleteUserByAi(anyLong(), anyLong());
    }

    @Test
    @DisplayName("用户 ID 缺失时校验失败")
    void shouldRejectWhenUserIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result = tool.callAsync(param(DeleteUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("用户 ID 不能为空"));
    }

    @Test
    @DisplayName("删除成功")
    void shouldDeleteSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:remove"));
        ToolResultBlock result =
            tool.callAsync(param(DeleteUserTool.TOOL_NAME, Map.of("user_id", "7"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("用户已删除（user_id：7）"));
        verify(userService).deleteUserByAi(7L, 100L);
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new ServiceException("不能删除超级管理员")).when(userService).deleteUserByAi(anyLong(), anyLong());
        ToolResultBlock result =
            tool.callAsync(param(DeleteUserTool.TOOL_NAME, Map.of("user_id", "1"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("不能删除超级管理员"));
    }

    @Test
    @DisplayName("未知异常时包装为删除失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new IllegalStateException("boom")).when(userService).deleteUserByAi(anyLong(), anyLong());
        ToolResultBlock result =
            tool.callAsync(param(DeleteUserTool.TOOL_NAME, Map.of("user_id", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("删除用户失败：boom"));
    }
}