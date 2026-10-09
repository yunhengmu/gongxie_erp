package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDetailDTO;
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
 * {@link GetNoticeTool} 单元测试：操作人 / 权限闸门、ID 校验、缺失与详情渲染。
 */
@DisplayName("查看公告详情工具")
class GetNoticeToolTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GetNoticeTool tool = new GetNoticeTool(noticeService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(GetNoticeTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(noticeService, never()).selectNoticeDetailByAi(anyLong());
    }

    @Test
    @DisplayName("无通知公告查询权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(GetNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「通知公告查询」权限"));
    }

    @Test
    @DisplayName("公告 ID 缺失时校验失败")
    void shouldRejectWhenNoticeIdMissing() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result = tool.callAsync(param(GetNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告 ID 不能为空"));
    }

    @Test
    @DisplayName("公告不存在时给出未找到文案")
    void shouldHandleMissingNotice() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.selectNoticeDetailByAi(3L)).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(GetNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未找到公告"));
    }

    @Test
    @DisplayName("查询成功时渲染公告详情")
    void shouldRenderNoticeDetail() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        NoticeDetailDTO dto = new NoticeDetailDTO();
        dto.setNoticeId(3L);
        dto.setNoticeTitle("系统维护公告");
        dto.setNoticeType("2");
        dto.setStatus("0");
        dto.setNoticeContent("今晚维护");
        dto.setCreateByName("管理员");
        when(noticeService.selectNoticeDetailByAi(3L)).thenReturn(dto);
        ToolResultBlock result =
            tool.callAsync(param(GetNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("公告详情："));
        assertTrue(text(result).contains("标题：系统维护公告"));
        assertTrue(text(result).contains("今晚维护"));
    }

    @Test
    @DisplayName("查询异常时包装为查看失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.selectNoticeDetailByAi(3L)).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(GetNoticeTool.TOOL_NAME, Map.of("notice_id", "3"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("查看公告失败：boom"));
    }
}