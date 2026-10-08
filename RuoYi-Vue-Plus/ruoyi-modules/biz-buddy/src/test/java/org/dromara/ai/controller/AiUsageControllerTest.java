package org.dromara.ai.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.ai.domain.bo.AiUsageStatBo;
import org.dromara.ai.domain.vo.AiUsageOverviewVo;
import org.dromara.ai.domain.vo.AiUsageStatExportVo;
import org.dromara.ai.domain.vo.AiUsageStatVo;
import org.dromara.ai.service.IAiUsageStatService;
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
 * {@link AiUsageController} 单元测试：用量明细、总览与导出。
 */
@DisplayName("AI 用量与配额 Controller")
class AiUsageControllerTest {

    private final IAiUsageStatService service = mock(IAiUsageStatService.class);

    private final AiUsageController controller = new AiUsageController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询用量明细")
    void shouldList() {
        AiUsageStatBo bo = new AiUsageStatBo();
        PageQuery query = new PageQuery();
        PageResult<AiUsageStatVo> page = PageResult.build();
        when(service.queryPage(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询用量总览")
    void shouldOverview() {
        AiUsageStatBo bo = new AiUsageStatBo();
        AiUsageOverviewVo vo = new AiUsageOverviewVo();
        when(service.overview(bo)).thenReturn(vo);
        assertSame(vo, controller.overview(bo).getData());
    }

    @Test
    @DisplayName("导出用量明细写入响应")
    void shouldExport() {
        AiUsageStatBo bo = new AiUsageStatBo();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(service.buildExportData(bo)).thenReturn(List.of(new AiUsageStatExportVo()));
        try (MockedStatic<ExcelBuilder> mocked = mockStatic(ExcelBuilder.class)) {
            ExcelBuilder builder = mock(ExcelBuilder.class);
            mocked.when(() -> ExcelBuilder.of(anyList(), any())).thenReturn(builder);
            when(builder.sheetName(anyString())).thenReturn(builder);
            controller.export(bo, response);
            verify(builder).toResponse(response);
        }
    }

}