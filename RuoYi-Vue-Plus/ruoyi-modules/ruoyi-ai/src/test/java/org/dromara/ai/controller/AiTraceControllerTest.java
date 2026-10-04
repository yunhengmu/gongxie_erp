package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiTraceBo;
import org.dromara.ai.domain.vo.AiTraceVo;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiTraceController} 单元测试：链路列表与详情查询。
 */
@DisplayName("AI 链路追踪 Controller")
class AiTraceControllerTest {

    private final IAiTraceService service = mock(IAiTraceService.class);

    private final AiTraceController controller = new AiTraceController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询链路列表")
    void shouldList() {
        AiTraceBo bo = new AiTraceBo();
        PageQuery query = new PageQuery();
        PageResult<AiTraceVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询链路详情")
    void shouldGetInfo() {
        AiTraceVo vo = new AiTraceVo();
        when(service.queryById(6L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(6L).getData());
    }

}