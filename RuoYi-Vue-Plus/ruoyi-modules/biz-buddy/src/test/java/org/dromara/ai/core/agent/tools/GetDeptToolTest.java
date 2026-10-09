package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptDetailDTO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GetDeptTool} 单元测试：操作人 / 权限闸门、ID 校验、缺失与详情渲染。
 */
@DisplayName("查看部门详情工具")
class GetDeptToolTest {

    private final DeptService deptService = mock(DeptService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetDeptTool tool = new GetDeptTool(deptService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(GetDeptTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(deptService, never()).selectDeptDetailByAi(anyLong(), anyLong());
    }

    @Test
    @DisplayName("无部门查询权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(GetDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「部门查询」权限"));
    }

    @Test
    @DisplayName("部门 ID 缺失时校验失败")
    void shouldRejectWhenDeptIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result = tool.callAsync(param(GetDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("部门 ID 不能为空"));
    }

    @Test
    @DisplayName("部门不存在时给出未找到文案")
    void shouldHandleMissingDept() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.selectDeptDetailByAi(5L, 100L)).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(GetDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未找到部门"));
    }

    @Test
    @DisplayName("查询成功时渲染部门详情")
    void shouldRenderDeptDetail() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        DeptDetailDTO dto = new DeptDetailDTO();
        dto.setDeptId(5L);
        dto.setDeptName("研发部");
        dto.setParentId(1L);
        dto.setParentName("总部");
        dto.setDeptCategory("RD");
        dto.setOrderNum(2);
        dto.setStatus("0");
        when(deptService.selectDeptDetailByAi(5L, 100L)).thenReturn(dto);
        ToolResultBlock result =
            tool.callAsync(param(GetDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("部门详情："));
        assertTrue(text(result).contains("部门名称：研发部"));
        assertTrue(text(result).contains("状态：正常"));
    }

    @Test
    @DisplayName("查询异常时包装为查询失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.selectDeptDetailByAi(5L, 100L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(GetDeptTool.TOOL_NAME, Map.of("dept_id", "5"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查询部门失败：boom"));
    }
}