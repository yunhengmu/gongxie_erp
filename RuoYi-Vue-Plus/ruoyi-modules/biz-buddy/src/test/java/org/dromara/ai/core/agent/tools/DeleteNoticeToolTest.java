package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
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
 * {@link DeleteNoticeTool} 单元测试：操作人 / 权限闸门、ID 校验与结果封装。
 */
@DisplayName("删除公告工具")
class DeleteNoticeToolTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final DeleteNoticeTool tool = new DeleteNoticeTool(noticeService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(DeleteNoticeTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(noticeService, never()).deleteNoticeByAi(anyLong());
    }

    @Test
    @DisplayName("无通知公告删除权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(DeleteNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「通知公告删除」权限"));
    }

    @Test
    @DisplayName("公告 ID 缺失时校验失败")
    void shouldRejectWhenNoticeIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result = tool.callAsync(param(DeleteNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告 ID 不能为空"));
    }

    @Test
    @DisplayName("删除成功")
    void shouldDeleteSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:notice:remove"));
        ToolResultBlock result =
            tool.callAsync(param(DeleteNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("公告已删除（notice_id：3）"));
        verify(noticeService).deleteNoticeByAi(3L);
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new ServiceException("公告不存在")).when(noticeService).deleteNoticeByAi(anyLong());
        ToolResultBlock result =
            tool.callAsync(param(DeleteNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告不存在"));
    }

    @Test
    @DisplayName("未知异常时包装为删除失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new IllegalStateException("boom")).when(noticeService).deleteNoticeByAi(anyLong());
        ToolResultBlock result =
            tool.callAsync(param(DeleteNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("删除公告失败：boom"));
    }
}