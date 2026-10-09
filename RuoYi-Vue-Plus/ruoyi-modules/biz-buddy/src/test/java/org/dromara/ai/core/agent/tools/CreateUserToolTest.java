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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CreateUserTool} 单元测试：操作人 / 权限闸门、入参校验与结果封装。
 */
@DisplayName("新增用户工具")
class CreateUserToolTest {

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final CreateUserTool tool = new CreateUserTool(userService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝且不写入")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, Map.of())).block();
        assertNotNull(result);
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(userService, never()).createUserByAi(any(UserSaveDTO.class));
    }

    @Test
    @DisplayName("无用户新增权限时拒绝且不写入")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:list"));
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「用户新增」权限"));
        verify(userService, never()).createUserByAi(any(UserSaveDTO.class));
    }

    @Test
    @DisplayName("账号为空时校验失败")
    void shouldRejectWhenUserNameBlank() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(CreateUserTool.TOOL_NAME, Map.of("nick_name", "张三"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("用户账号不能为空"));
        verify(userService, never()).createUserByAi(any(UserSaveDTO.class));
    }

    @Test
    @DisplayName("邮箱格式非法时校验失败")
    void shouldRejectWhenEmailInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input =
            Map.of("user_name", "zhangsan", "nick_name", "张三", "email", "not-an-email");
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("邮箱格式不正确"));
    }

    @Test
    @DisplayName("新增成功且分配角色时不提示缺角色")
    void shouldCreateSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:add"));
        when(userService.createUserByAi(any(UserSaveDTO.class))).thenReturn(55L);
        Map<String, Object> input = Map.of("user_name", "zhangsan", "nick_name", "张三",
            "role_ids", List.of("1", "2"));
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, input, "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("user_id：55"));
        assertFalse(text(result).contains("未分配角色"));
        verify(userService).createUserByAi(any(UserSaveDTO.class));
    }

    @Test
    @DisplayName("未提供角色时提示暂无菜单权限")
    void shouldWarnWhenNoRole() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:add"));
        when(userService.createUserByAi(any(UserSaveDTO.class))).thenReturn(56L);
        Map<String, Object> input = Map.of("user_name", "lisi", "nick_name", "李四");
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(text(result).contains("未分配角色"));
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:add"));
        when(userService.createUserByAi(any(UserSaveDTO.class))).thenThrow(new ServiceException("账号已存在"));
        Map<String, Object> input = Map.of("user_name", "zhangsan", "nick_name", "张三");
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("账号已存在"));
    }

    @Test
    @DisplayName("未知异常时包装为新增失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:add"));
        when(userService.createUserByAi(any(UserSaveDTO.class))).thenThrow(new IllegalStateException("boom"));
        Map<String, Object> input = Map.of("user_name", "zhangsan", "nick_name", "张三");
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("新增用户失败：boom"));
    }

    @Test
    @DisplayName("角色 ID 非数字时校验失败")
    void shouldRejectWhenRoleIdNotNumeric() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:user:add"));
        Map<String, Object> input = Map.of("user_name", "zhangsan", "nick_name", "张三",
            "role_ids", List.of("abc"));
        ToolResultBlock result = tool.callAsync(param(CreateUserTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertEquals("Error: 角色 ID 必须是数字，收到：abc", text(result));
    }
}