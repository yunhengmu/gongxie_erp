package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDetailDTO;
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
 * {@link SearchNoticeTool} 单元测试：操作人 / 权限闸门、类型状态校验与结果渲染。
 */
@DisplayName("搜索公告工具")
class SearchNoticeToolTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SearchNoticeTool tool = new SearchNoticeTool(noticeService, permissionService);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static NoticeDetailDTO notice() {
        NoticeDetailDTO dto = new NoticeDetailDTO();
        dto.setNoticeId(3L);
        dto.setNoticeTitle("系统维护公告");
        dto.setNoticeType("2");
        dto.setStatus("0");
        dto.setCreateByName("管理员");
        return dto;
    }

    @Test
    @DisplayName("无法识别操作人时拒绝")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前操作人"));
        verify(noticeService, never()).searchNoticesByAi(any(), any(), any());
    }

    @Test
    @DisplayName("无通知公告列表权限时拒绝")
    void shouldRejectWhenNoPermission() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of());
        ToolResultBlock result = tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无「通知公告列表」权限"));
    }

    @Test
    @DisplayName("公告类型非法时校验失败")
    void shouldRejectWhenTypeInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of("notice_type", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告类型只能是 1（通知）或 2（公告）"));
    }

    @Test
    @DisplayName("公告状态非法时校验失败")
    void shouldRejectWhenStatusInvalid() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        ToolResultBlock result =
            tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of("status", "9"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("公告状态只能是 0（正常）或 1（关闭）"));
    }

    @Test
    @DisplayName("搜索命中时渲染公告清单")
    void shouldRenderMatches() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.searchNoticesByAi(any(), any(), any())).thenReturn(List.of(notice()));
        ToolResultBlock result = tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("共 1 条公告"));
        assertTrue(text(result).contains("notice_id=3"));
        assertTrue(text(result).contains("类型=公告"));
    }

    @Test
    @DisplayName("搜索无命中时给出空结果文案")
    void shouldHandleEmptyResult() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.searchNoticesByAi(any(), any(), any())).thenReturn(List.of());
        ToolResultBlock result = tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(text(result).contains("没有匹配的公告"));
    }

    @Test
    @DisplayName("查询异常时包装为搜索失败")
    void shouldReturnUnexpectedFailure() {
        when(permissionService.getMenuPermission(100L)).thenReturn(Set.of("*:*:*"));
        when(noticeService.searchNoticesByAi(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result = tool.callAsync(param(SearchNoticeTool.TOOL_NAME, Map.of(), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("搜索公告失败：boom"));
    }
}