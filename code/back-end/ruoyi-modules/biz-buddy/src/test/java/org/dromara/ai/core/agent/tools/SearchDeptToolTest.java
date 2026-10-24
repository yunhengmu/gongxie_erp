package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptDetailDTO;
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
 * {@link SearchDeptTool} 单元测试：操作人 / 权限闸门、状态筛选校验与结果渲染。
 */
@DisplayName("搜索部门工具")
class SearchDeptToolTest {

    private final DeptService deptService = mock(DeptService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SearchDeptTool tool = new SearchDeptTool(deptService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static DeptDetailDTO dept() {
        DeptDetailDTO dto = new DeptDetailDTO();
        dto.setDeptId(3L);
        dto.setDeptName("研发部");
        dto.setParentId(1L);
        dto.setParentName("总部");
        dto.setOrderNum(0);
        dto.setStatus("0");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(SearchDeptTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(deptService, never()).searchDeptsByAi(any(), any(), any());
    }

    @Test
    @DisplayName("无部门列表权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(SearchDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「部门列表」权限"));
    }

    @Test
    @DisplayName("状态非法时校验失败")
    void shouldRejectWhenStatusInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchDeptTool.TOOL_NAME, Map.of("status", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("状态只能是 0（正常）或 1（停用）"));
    }

    @Test
    @DisplayName("搜索命中时渲染部门清单")
    void shouldRenderMatches() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.searchDeptsByAi(any(), any(), any())).thenReturn(List.of(dept()));
        ToolResultBlock result = tool.callAsync(param(SearchDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("共 1 个部门"));
        assertTrue(text(result).contains("dept_id=3"));
        assertTrue(text(result).contains("上级=总部"));
    }

    @Test
    @DisplayName("搜索无命中时给出空结果文案")
    void shouldHandleEmptyResult() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.searchDeptsByAi(any(), any(), any())).thenReturn(List.of());
        ToolResultBlock result = tool.callAsync(param(SearchDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(text(result).contains("没有匹配的部门"));
    }

    @Test
    @DisplayName("查询异常时包装为搜索失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(deptService.searchDeptsByAi(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(SearchDeptTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("搜索部门失败：boom"));
    }
}