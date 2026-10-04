package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDTO;
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
 * {@link CreateNoticeTool} 单元测试：操作人 / 权限闸门、标题正文校验与结果封装。
 */
@DisplayName("发布系统公告工具")
class CreateNoticeToolTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final CreateNoticeTool tool = new CreateNoticeTool(noticeService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(CreateNoticeTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(noticeService, never()).createNoticeByAi(any(NoticeDTO.class));
    }

    @Test
    @DisplayName("无通知公告新增权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:notice:list"));
        ToolResultBlock result =
            tool.callAsync(param(CreateNoticeTool.TOOL_NAME, Map.of("notice_title", "标题"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「通知公告新增」权限"));
    }

    @Test
    @DisplayName("标题为空时校验失败")
    void shouldRejectWhenTitleBlank() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(CreateNoticeTool.TOOL_NAME, Map.of("notice_content", "正文"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告标题不能为空"));
    }

    @Test
    @DisplayName("标题超长时校验失败")
    void shouldRejectWhenTitleTooLong() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        Map<String, Object> input = Map.of("notice_title", "x".repeat(51), "notice_content", "正文");
        ToolResultBlock result = tool.callAsync(param(CreateNoticeTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告标题不能超过 50 个字符"));
    }

    @Test
    @DisplayName("正文为空时校验失败")
    void shouldRejectWhenContentBlank() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(CreateNoticeTool.TOOL_NAME, Map.of("notice_title", "标题"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告正文不能为空"));
    }

    @Test
    @DisplayName("发布成功")
    void shouldCreateSuccessfully() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("system:notice:add"));
        when(noticeService.createNoticeByAi(any(NoticeDTO.class))).thenReturn(3L);
        Map<String, Object> input = Map.of("notice_title", "标题", "notice_content", "正文");
        ToolResultBlock result = tool.callAsync(param(CreateNoticeTool.TOOL_NAME, input, "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("公告已发布（通知 ID：3）"));
        verify(noticeService).createNoticeByAi(any(NoticeDTO.class));
    }

    @Test
    @DisplayName("业务校验失败时回喂异常信息")
    void shouldReturnBusinessFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.createNoticeByAi(any(NoticeDTO.class))).thenThrow(new ServiceException("标题已存在"));
        Map<String, Object> input = Map.of("notice_title", "标题", "notice_content", "正文");
        ToolResultBlock result = tool.callAsync(param(CreateNoticeTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("标题已存在"));
    }

    @Test
    @DisplayName("未知异常时包装为发布失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.createNoticeByAi(any(NoticeDTO.class))).thenThrow(new IllegalStateException("boom"));
        Map<String, Object> input = Map.of("notice_title", "标题", "notice_content", "正文");
        ToolResultBlock result = tool.callAsync(param(CreateNoticeTool.TOOL_NAME, input, "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("发布公告失败：boom"));
    }
}