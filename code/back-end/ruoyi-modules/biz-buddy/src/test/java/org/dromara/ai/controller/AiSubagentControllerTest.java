package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiSubagentBo;
import org.dromara.ai.domain.vo.AiSubagentOptionVo;
import org.dromara.ai.domain.vo.AiSubagentVo;
import org.dromara.ai.service.IAiSubagentService;
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
 * {@link AiSubagentController} 单元测试：子智能体 CRUD 与选项。
 */
@DisplayName("AI 子智能体 Controller")
class AiSubagentControllerTest {

    private final IAiSubagentService service = mock(IAiSubagentService.class);

    private final AiSubagentController controller = new AiSubagentController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询子智能体列表")
    void shouldList() {
        AiSubagentBo bo = new AiSubagentBo();
        PageQuery query = new PageQuery();
        PageResult<AiSubagentVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询子智能体选项")
    void shouldOptions() {
        List<AiSubagentOptionVo> options = List.of(new AiSubagentOptionVo());
        when(service.queryOptions()).thenReturn(options);
        assertSame(options, controller.options().getData());
    }

    @Test
    @DisplayName("获取子智能体详情")
    void shouldGetInfo() {
        AiSubagentVo vo = new AiSubagentVo();
        when(service.queryById(3L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(3L).getData());
    }

    @Test
    @DisplayName("新增子智能体成功")
    void shouldAdd() {
        AiSubagentBo bo = new AiSubagentBo();
        when(service.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改子智能体成功")
    void shouldEdit() {
        AiSubagentBo bo = new AiSubagentBo();
        when(service.updateByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("批量删除子智能体失败返回失败码")
    void shouldRemoveFail() {
        when(service.deleteWithValidByIds(List.of(1L, 2L))).thenReturn(false);
        assertFalse(R.isSuccess(controller.remove(new Long[]{1L, 2L})));
    }

}