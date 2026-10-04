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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CreateDeptTool} 单元测试：操作人 / 权限闸门、入参校验与结果封装。
 */
@DisplayName("新增部门工具")
class CreateDeptToolTest {

    private final DeptService deptService = mock(DeptService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final CreateDeptTool tool = new CreateDeptTool(deptService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(CreateDeptTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(deptService, never()).createDeptByAi(any(DeptSaveDTO.class));
    }

    @Test
    @DisplayName("无部门新增权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(CreateDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「部门新增」权限"));
    }

    @Test
    @DisplayName("部门名称为空时校验失败")
    void shouldRejectWhenDeptNameBlank() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(CreateDeptTool.TOOL_NAME, Map.of("parent_id", "1"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("部门名称不能为空"));
    }

    @Test
    @DisplayName("上级部门 ID 缺失时校验失败")
    void shouldRejectWhenParentMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(CreateDeptTool.TOOL_NAME, Map.of("dept_name", "研发部"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("上级部门 ID 不能为空"));
    }

    @Test
    @DisplayName("显示顺序为负数时校验失败")
    void shouldRejectWhenOrderNumNegative() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("dept_name", "研发部", "parent_id", "1", "order_num", "-1");
        ToolResultBlock result = tool.callAsync(param(CreateDeptTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("显示顺序必须是不小于 0 的整数"));
    }

    @Test
    @DisplayName("新增成功")
    void shouldCreateSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:dept:add"));
        when(deptService.createDeptByAi(any(DeptSaveDTO.class))).thenReturn(9L);
        Map<String, Object> input = Map.of("dept_name", "研发部", "parent_id", "1");
        ToolResultBlock result = tool.callAsync(param(CreateDeptTool.TOOL_NAME, input, "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("dept_id：9"));
        verify(deptService).createDeptByAi(any(DeptSaveDTO.class));
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.createDeptByAi(any(DeptSaveDTO.class))).thenThrow(new ServiceException("同名部门已存在"));
        Map<String, Object> input = Map.of("dept_name", "研发部", "parent_id", "1");
        ToolResultBlock result = tool.callAsync(param(CreateDeptTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("同名部门已存在"));
    }

    @Test
    @DisplayName("未知异常时包装为新增失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.createDeptByAi(any(DeptSaveDTO.class))).thenThrow(new IllegalStateException("boom"));
        Map<String, Object> input = Map.of("dept_name", "研发部", "parent_id", "1");
        ToolResultBlock result = tool.callAsync(param(CreateDeptTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("新增部门失败：boom"));
    }
}