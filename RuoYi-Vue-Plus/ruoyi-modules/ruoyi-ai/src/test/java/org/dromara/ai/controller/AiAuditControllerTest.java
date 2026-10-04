package org.dromara.ai.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.ai.domain.bo.AiMessageAuditBo;
import org.dromara.ai.domain.bo.AiToolCallAuditBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageAuditExportVo;
import org.dromara.ai.domain.vo.AiMessageAuditVo;
import org.dromara.ai.domain.vo.AiToolCallAuditVo;
import org.dromara.ai.domain.vo.AiToolCallStatVo;
import org.dromara.ai.service.IAiAuditService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiAuditController} 单元测试：消息与工具调用审计查询、导出与智能体选项。
 */
@DisplayName("AI 审计 Controller")
class AiAuditControllerTest {

    private final IAiAuditService service = mock(IAiAuditService.class);

    private final AiAuditController controller = new AiAuditController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询会话消息")
    void shouldMessageList() {
        AiMessageAuditBo bo = new AiMessageAuditBo();
        PageQuery query = new PageQuery();
        PageResult<AiMessageAuditVo> page = PageResult.build();
        when(service.queryMessagePage(bo, query)).thenReturn(page);
        assertSame(page, controller.messageList(bo, query).getData());
    }

    @Test
    @DisplayName("导出会话消息写入响应")
    void shouldMessageExport() {
        AiMessageAuditBo bo = new AiMessageAuditBo();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(service.buildMessageExport(bo)).thenReturn(List.of(new AiMessageAuditExportVo()));
        try (MockedStatic<ExcelBuilder> mocked = mockStatic(ExcelBuilder.class)) {
            ExcelBuilder builder = mock(ExcelBuilder.class);
            mocked.when(() -> ExcelBuilder.of(anyList(), any())).thenReturn(builder);
            when(builder.sheetName(anyString())).thenReturn(builder);
            controller.messageExport(bo, response);
            verify(builder).toResponse(response);
        }
    }

    @Test
    @DisplayName("分页查询工具调用明细与统计")
    void shouldToolCallListAndStats() {
        AiToolCallAuditBo bo = new AiToolCallAuditBo();
        PageQuery query = new PageQuery();
        PageResult<AiToolCallAuditVo> page = PageResult.build();
        List<AiToolCallStatVo> stats = List.of(new AiToolCallStatVo());
        when(service.queryToolCallPage(bo, query)).thenReturn(page);
        when(service.queryToolCallStats(bo)).thenReturn(stats);
        assertSame(page, controller.toolCallList(bo, query).getData());
        assertSame(stats, controller.toolCallStats(bo).getData());
    }

    @Test
    @DisplayName("查询运行数据中出现过的智能体选项")
    void shouldAgentOptions() {
        List<AiAgentVo> options = List.of(new AiAgentVo());
        when(service.queryReferencedAgentOptions()).thenReturn(options);
        assertSame(options, controller.agentOptions().getData());
    }

}