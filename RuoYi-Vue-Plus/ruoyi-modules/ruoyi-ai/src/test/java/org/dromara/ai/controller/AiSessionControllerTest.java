package org.dromara.ai.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiSessionExportVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiSessionController} 单元测试：会话治理查询、终止、删除与导出。
 */
@DisplayName("AI 会话管理 Controller")
class AiSessionControllerTest {

    private final IAiSessionService service = mock(IAiSessionService.class);

    private final AiSessionController controller = new AiSessionController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询全部会话")
    void shouldList() {
        AiSessionBo bo = new AiSessionBo();
        PageQuery query = new PageQuery();
        PageResult<AiSessionVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询出现过会话的智能体选项")
    void shouldAgentOptions() {
        List<AiAgentVo> options = List.of(new AiAgentVo());
        when(service.queryAgentOptions()).thenReturn(options);
        assertSame(options, controller.agentOptions().getData());
    }

    @Test
    @DisplayName("查询会话消息与工具调用明细")
    void shouldMessagesAndToolCalls() {
        List<AiMessageVo> messages = List.of(new AiMessageVo());
        List<AiToolCallLogVo> calls = List.of(new AiToolCallLogVo());
        when(service.querySessionMessages(2L)).thenReturn(messages);
        when(service.queryToolCalls(2L)).thenReturn(calls);
        assertSame(messages, controller.messages(2L).getData());
        assertSame(calls, controller.toolCalls(2L).getData());
    }

    @Test
    @DisplayName("终止会话成功")
    void shouldClose() {
        when(service.closeWithValidByIds(List.of(2L))).thenReturn(true);
        assertTrue(R.isSuccess(controller.close(new Long[]{2L})));
    }

    @Test
    @DisplayName("删除会话失败返回失败码")
    void shouldRemoveFail() {
        when(service.deleteWithValidByIds(List.of(2L))).thenReturn(false);
        assertFalse(R.isSuccess(controller.remove(new Long[]{2L})));
    }

    @Test
    @DisplayName("导出会话留档写入响应")
    void shouldExport() {
        AiSessionBo bo = new AiSessionBo();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(service.buildExportData(bo)).thenReturn(List.of(new AiSessionExportVo()));
        try (MockedStatic<ExcelBuilder> mocked = mockStatic(ExcelBuilder.class)) {
            ExcelBuilder builder = mock(ExcelBuilder.class);
            mocked.when(() -> ExcelBuilder.of(anyList(), any())).thenReturn(builder);
            when(builder.sheetName(anyString())).thenReturn(builder);
            controller.export(bo, response);
            verify(builder).toResponse(response);
        }
    }

}