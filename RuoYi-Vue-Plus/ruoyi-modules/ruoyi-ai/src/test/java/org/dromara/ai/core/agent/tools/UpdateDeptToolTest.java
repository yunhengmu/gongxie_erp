package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptSaveDTO;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UpdateDeptTool} 单元测试：操作人 / 权限闸门、部分字段校验与结果封装。
 */
@DisplayName("修改部门工具")
class UpdateDeptToolTest {

    private final DeptService deptService = mock(DeptService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final UpdateDeptTool tool = new UpdateDeptTool(deptService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(UpdateDeptTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(deptService, never()).updateDeptByAi(any(DeptSaveDTO.class));
    }

    @Test
    @DisplayName("无部门修改权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(UpdateDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「部门修改」权限"));
    }

    @Test
    @DisplayName("缺少部门 ID 时校验失败")
    void shouldRejectWhenDeptIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(UpdateDeptTool.TOOL_NAME, Map.of("dept_name", "研发部"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("部门 ID 不能为空"));
    }

    @Test
    @DisplayName("上级部门 ID 非数字时校验失败")
    void shouldRejectWhenParentNotNumeric() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("dept_id", "5", "parent_id", "abc");
        ToolResultBlock result = tool.callAsync(param(UpdateDeptTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("上级部门 ID 必须是数字"));
    }

    @Test
    @DisplayName("显示顺序非法时校验失败")
    void shouldRejectWhenOrderNumInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("dept_id", "5", "order_num", "-2");
        ToolResultBlock result = tool.callAsync(param(UpdateDeptTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("显示顺序必须是不小于 0 的整数"));
    }

    @Test
    @DisplayName("修改成功")
    void shouldUpdateSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:dept:edit"));
        ToolResultBlock result =
            tool.callAsync(param(UpdateDeptTool.TOOL_NAME, Map.of("dept_id", "5", "dept_name", "新名称"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("部门已修改（dept_id：5）"));
        verify(deptService).updateDeptByAi(any(DeptSaveDTO.class));
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new ServiceException("停用前需确保无下级部门")).when(deptService).updateDeptByAi(any(DeptSaveDTO.class));
        ToolResultBlock result =
            tool.callAsync(param(UpdateDeptTool.TOOL_NAME, Map.of("dept_id", "5", "status", "1"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("停用前需确保无下级部门"));
    }

    @Test
    @DisplayName("未知异常时包装为修改失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new IllegalStateException("boom")).when(deptService).updateDeptByAi(any(DeptSaveDTO.class));
        ToolResultBlock result =
            tool.callAsync(param(UpdateDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("修改部门失败：boom"));
    }
}