package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiScenarioPackBo;
import org.dromara.ai.domain.vo.AiScenarioPackMcpOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackVo;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiScenarioPackController} 单元测试：场景包 CRUD 与选项。
 */
@DisplayName("AI 场景包 Controller")
class AiScenarioPackControllerTest {

    private final IAiScenarioPackService service = mock(IAiScenarioPackService.class);

    private final AiScenarioPackController controller = new AiScenarioPackController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询场景包列表")
    void shouldList() {
        AiScenarioPackBo bo = new AiScenarioPackBo();
        PageQuery query = new PageQuery();
        PageResult<AiScenarioPackVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询场景包选项与 MCP 工具选项")
    void shouldOptions() {
        List<AiScenarioPackOptionVo> options = List.of(new AiScenarioPackOptionVo());
        List<AiScenarioPackMcpOptionVo> mcp = List.of(new AiScenarioPackMcpOptionVo());
        when(service.queryOptions()).thenReturn(options);
        when(service.queryMcpToolOptions()).thenReturn(mcp);
        assertSame(options, controller.options().getData());
        assertSame(mcp, controller.mcpToolOptions().getData());
    }

    @Test
    @DisplayName("获取场景包详情")
    void shouldGetInfo() {
        AiScenarioPackVo vo = new AiScenarioPackVo();
        when(service.queryById(2L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(2L).getData());
    }

    @Test
    @DisplayName("新增场景包成功")
    void shouldAdd() {
        AiScenarioPackBo bo = new AiScenarioPackBo();
        when(service.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改场景包失败返回失败码")
    void shouldEditFail() {
        AiScenarioPackBo bo = new AiScenarioPackBo();
        when(service.updateByBo(bo)).thenReturn(false);
        assertFalse(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("批量删除场景包")
    void shouldRemove() {
        when(service.deleteWithValidByIds(List.of(1L))).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{1L})));
    }

}