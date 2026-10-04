package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GetCurrentUserTool} 单元测试：操作人缺失、用户不存在、成功渲染与异常包装。
 */
@DisplayName("获取当前用户信息工具")
class GetCurrentUserToolTest {

    private final UserService userService = mock(UserService.class);
    private final GetCurrentUserTool tool = new GetCurrentUserTool(userService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别提问用户身份时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(GetCurrentUserTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前提问用户的平台身份"));
        verify(userService, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("用户不存在时给出提示")
    void shouldHandleMissingUser() {
        when(userService.selectById(100L)).thenReturn(null);
        ToolResultBlock result = tool.callAsync(param(GetCurrentUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("未查到当前用户信息"));
    }

    @Test
    @DisplayName("查询成功时渲染本人账号信息")
    void shouldRenderCurrentUser() {
        UserDTO user = new UserDTO();
        user.setUserId(100L);
        user.setUserName("zhangsan");
        user.setNickName("张三");
        user.setDeptId(3L);
        user.setGender("0");
        user.setStatus("0");
        when(userService.selectById(100L)).thenReturn(user);
        ToolResultBlock result = tool.callAsync(param(GetCurrentUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("用户账号：zhangsan"));
        assertTrue(text(result).contains("性别：男"));
        assertTrue(text(result).contains("是否超级管理员：否"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(userService.selectById(100L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(GetCurrentUserTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询当前用户信息失败：boom"));
    }
}