package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
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
 * {@link DeleteDeptTool} 单元测试：操作人 / 权限闸门、ID 校验与结果封装。
 */
@DisplayName("删除部门工具")
class DeleteDeptToolTest {

    private final DeptService deptService = mock(DeptService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final DeleteDeptTool tool = new DeleteDeptTool(deptService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(DeleteDeptTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(deptService, never()).deleteDeptByAi(anyLong(), anyLong());
    }

    @Test
    @DisplayName("无部门删除权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(DeleteDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「部门删除」权限"));
    }

    @Test
    @DisplayName("部门 ID 缺失时校验失败")
    void shouldRejectWhenDeptIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result = tool.callAsync(param(DeleteDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("部门 ID 不能为空"));
    }

    @Test
    @DisplayName("删除成功")
    void shouldDeleteSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:dept:remove"));
        ToolResultBlock result =
            tool.callAsync(param(DeleteDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("部门已删除（dept_id：5）"));
        verify(deptService).deleteDeptByAi(5L, 100L);
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new ServiceException("存在下级部门，无法删除")).when(deptService).deleteDeptByAi(anyLong(), anyLong());
        ToolResultBlock result =
            tool.callAsync(param(DeleteDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("存在下级部门，无法删除"));
    }

    @Test
    @DisplayName("未知异常时包装为删除失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new IllegalStateException("boom")).when(deptService).deleteDeptByAi(anyLong(), anyLong());
        ToolResultBlock result =
            tool.callAsync(param(DeleteDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("删除部门失败：boom"));
    }
}