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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SearchUserTool} 单元测试：操作人 / 权限闸门、筛选参数校验与结果渲染。
 */
@DisplayName("搜索用户工具")
class SearchUserToolTest {

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SearchUserTool tool = new SearchUserTool(userService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static UserDetailDTO user() {
        UserDetailDTO dto = new UserDetailDTO();
        dto.setUserId(1L);
        dto.setUserName("zhangsan");
        dto.setNickName("张三");
        dto.setDeptName("研发部");
        dto.setPhoneNumber("13800000000");
        dto.setStatus("0");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(userService, never()).searchUsersByAi(any(), any(), any(), any());
    }

    @Test
    @DisplayName("无用户列表权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「用户列表」权限"));
    }

    @Test
    @DisplayName("部门 ID 非数字时校验失败")
    void shouldRejectWhenDeptIdNotNumeric() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of("dept_id", "abc"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("部门 ID 必须是数字"));
    }

    @Test
    @DisplayName("状态非法时校验失败")
    void shouldRejectWhenStatusInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of("status", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("账号状态只能是"));
    }

    @Test
    @DisplayName("搜索命中时渲染用户清单")
    void shouldRenderMatches() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(userService.searchUsersByAi(any(), any(), any(), any())).thenReturn(List.of(user()));
        ToolResultBlock result = tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("共 1 个用户"));
        assertTrue(text(result).contains("user_id=1"));
        assertTrue(text(result).contains("研发部"));
    }

    @Test
    @DisplayName("搜索无命中时给出空结果文案")
    void shouldHandleEmptyResult() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(userService.searchUsersByAi(any(), any(), any(), any())).thenReturn(List.of());
        ToolResultBlock result = tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(text(result).contains("没有匹配的用户"));
    }

    @Test
    @DisplayName("查询异常时包装为搜索失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(userService.searchUsersByAi(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("db down"));
        ToolResultBlock result = tool.callAsync(param(SearchUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("搜索用户失败：db down"));
    }
}