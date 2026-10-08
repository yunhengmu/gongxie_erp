package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeSaveDTO;
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
 * {@link UpdateNoticeTool} 单元测试：操作人 / 权限闸门、部分字段校验与结果封装。
 */
@DisplayName("修改公告工具")
class UpdateNoticeToolTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final UpdateNoticeTool tool = new UpdateNoticeTool(noticeService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(noticeService, never()).updateNoticeByAi(any(NoticeSaveDTO.class));
    }

    @Test
    @DisplayName("无通知公告修改权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「通知公告修改」权限"));
    }

    @Test
    @DisplayName("缺少公告 ID 时校验失败")
    void shouldRejectWhenNoticeIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, Map.of("notice_title", "标题"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告 ID 不能为空"));
    }

    @Test
    @DisplayName("公告类型非法时校验失败")
    void shouldRejectWhenTypeInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("notice_id", "3", "notice_type", "9");
        ToolResultBlock result = tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告类型只能是 1（通知）或 2（公告）"));
    }

    @Test
    @DisplayName("公告状态非法时校验失败")
    void shouldRejectWhenStatusInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("notice_id", "3", "status", "9");
        ToolResultBlock result = tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告状态只能是 0（正常）或 1（关闭）"));
    }

    @Test
    @DisplayName("修改成功")
    void shouldUpdateSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:notice:edit"));
        ToolResultBlock result =
            tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, Map.of("notice_id", "3", "notice_title", "新标题"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("公告已修改（notice_id：3）"));
        verify(noticeService).updateNoticeByAi(any(NoticeSaveDTO.class));
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        doThrow(new ServiceException("公告不存在")).when(noticeService).updateNoticeByAi(any(NoticeSaveDTO.class));
        ToolResultBlock result =
            tool.callAsync(param(UpdateNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告不存在"));
    }
}