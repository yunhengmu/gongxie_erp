package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiRouteRunBo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiRouteRunStatsVo;
import org.dromara.ai.domain.vo.AiRouteRunVo;
import org.dromara.ai.service.IAiRouteService;
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
 * {@link AiRouteController} 单元测试：编排运行列表、统计与详情。
 */
@DisplayName("AI 编排运行 Controller")
class AiRouteControllerTest {

    private final IAiRouteService service = mock(IAiRouteService.class);

    private final AiRouteController controller = new AiRouteController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询编排运行列表")
    void shouldList() {
        AiRouteRunBo bo = new AiRouteRunBo();
        PageQuery query = new PageQuery();
        PageResult<AiRouteRunVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询编排运行统计")
    void shouldStats() {
        AiRouteRunBo bo = new AiRouteRunBo();
        AiRouteRunStatsVo vo = new AiRouteRunStatsVo();
        when(service.queryStats(bo)).thenReturn(vo);
        assertSame(vo, controller.stats(bo).getData());
    }

    @Test
    @DisplayName("查询编排运行详情")
    void shouldGetInfo() {
        AiRouteRunDetailVo vo = new AiRouteRunDetailVo();
        when(service.queryById(4L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(4L).getData());
    }

}