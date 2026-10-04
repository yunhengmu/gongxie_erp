package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.DeptDetailDTO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GetCurrentDeptTool} 单元测试：身份缺失、无部门、部门缺失、成功与异常包装。
 */
@DisplayName("获取当前用户所属部门工具")
class GetCurrentDeptToolTest {

    private final UserService userService = mock(UserService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final GetCurrentDeptTool tool = new GetCurrentDeptTool(userService, deptService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static UserDTO user(Long deptId) {
        UserDTO dto = new UserDTO();
        dto.setUserId(100L);
        dto.setDeptId(deptId);
        return dto;
    }

    @Test
    @DisplayName("无法识别提问用户身份时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(GetCurrentDeptTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前提问用户的平台身份"));
    }

    @Test
    @DisplayName("用户不存在时给出提示")
    void shouldHandleMissingUser() {
        when(userService.selectById(100L)).thenReturn(null);
        ToolResultBlock result = tool.callAsync(param(GetCurrentDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("未查到当前用户信息"));
    }

    @Test
    @DisplayName("用户未归属任何部门时给出提示")
    void shouldHandleUserWithoutDept() {
        when(userService.selectById(100L)).thenReturn(user(null));
        ToolResultBlock result = tool.callAsync(param(GetCurrentDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未归属任何部门"));
    }

    @Test
    @DisplayName("部门不存在时给出提示")
    void shouldHandleMissingDept() {
        when(userService.selectById(100L)).thenReturn(user(3L));
        when(deptService.selectDeptDetailByAi(3L, 100L)).thenReturn(null);
        ToolResultBlock result = tool.callAsync(param(GetCurrentDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("不存在或已被删除"));
    }

    @Test
    @DisplayName("查询成功时渲染所属部门信息")
    void shouldRenderCurrentDept() {
        when(userService.selectById(100L)).thenReturn(user(3L));
        DeptDetailDTO dept = new DeptDetailDTO();
        dept.setDeptId(3L);
        dept.setDeptName("研发部");
        dept.setParentId(1L);
        dept.setStatus("0");
        when(deptService.selectDeptDetailByAi(3L, 100L)).thenReturn(dept);
        ToolResultBlock result = tool.callAsync(param(GetCurrentDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("部门名称：研发部"));
        assertTrue(text(result).contains("以上为提问用户本人所属部门的信息"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(userService.selectById(100L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(GetCurrentDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询当前用户所属部门失败：boom"));
    }
}