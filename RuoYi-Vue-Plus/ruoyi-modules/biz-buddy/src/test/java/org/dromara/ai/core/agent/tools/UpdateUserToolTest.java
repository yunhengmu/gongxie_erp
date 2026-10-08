package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserSaveDTO;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UpdateUserTool} 单元测试：操作人 / 权限闸门、部分字段校验与结果封装。
 */
@DisplayName("修改用户工具")
class UpdateUserToolTest {

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final UpdateUserTool tool = new UpdateUserTool(userService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(UpdateUserTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(userService, never()).updateUserByAi(any(UserSaveDTO.class));
    }

    @Test
    @DisplayName("无用户修改权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(UpdateUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「用户修改」权限"));
    }

    @Test
    @DisplayName("缺少用户 ID 时校验失败")
    void shouldRejectWhenUserIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:edit"));
        ToolResultBlock result =
            tool.callAsync(param(UpdateUserTool.TOOL_NAME, Map.of("nick_name", "张三"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("用户 ID 不能为空"));
    }

    @Test
    @DisplayName("部门 ID 非数字时校验失败")
    void shouldRejectWhenDeptIdNotNumeric() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:edit"));
        Map<String, Object> input = Map.of("user_id", "7", "dept_id", "abc");
        ToolResultBlock result = tool.callAsync(param(UpdateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("部门 ID 必须是数字"));
    }

    @Test
    @DisplayName("清空角色时给出提示")
    void shouldWarnWhenRoleCleared() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:edit"));
        Map<String, Object> input = Map.of("user_id", "7", "role_ids", List.of());
        ToolResultBlock result = tool.callAsync(param(UpdateUserTool.TOOL_NAME, input, "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("角色已清空"));
        verify(userService).updateUserByAi(any(UserSaveDTO.class));
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new ServiceException("不允许操作超级管理员")).when(userService).updateUserByAi(any(UserSaveDTO.class));
        ToolResultBlock result =
            tool.callAsync(param(UpdateUserTool.TOOL_NAME, Map.of("user_id", "7"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("不允许操作超级管理员"));
    }

    @Test
    @DisplayName("未知异常时包装为修改失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new IllegalStateException("boom")).when(userService).updateUserByAi(any(UserSaveDTO.class));
        ToolResultBlock result =
            tool.callAsync(param(UpdateUserTool.TOOL_NAME, Map.of("user_id", "7"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("修改用户失败：boom"));
    }

    @Test
    @DisplayName("昵称超长时校验失败")
    void shouldRejectWhenNickNameTooLong() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("user_id", "7", "nick_name", "x".repeat(31));
        ToolResultBlock result = tool.callAsync(param(UpdateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("用户昵称不能为空且不超过"));
    }
}