package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiModelConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiModelTestVo;
import org.dromara.ai.service.IAiModelConfigService;
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
 * {@link AiModelConfigController} 单元测试：模型配置 CRUD、复制与连通性测试。
 */
@DisplayName("AI 模型配置 Controller")
class AiModelConfigControllerTest {

    private final IAiModelConfigService service = mock(IAiModelConfigService.class);

    private final AiModelConfigController controller = new AiModelConfigController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询模型列表")
    void shouldList() {
        AiModelConfigBo bo = new AiModelConfigBo();
        PageQuery query = new PageQuery();
        PageResult<AiModelConfigVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("按类型查询模型选项")
    void shouldOptions() {
        List<AiModelConfigVo> options = List.of(new AiModelConfigVo());
        when(service.queryOptions("chat")).thenReturn(options);
        assertSame(options, controller.options("chat").getData());
    }

    @Test
    @DisplayName("获取模型详情")
    void shouldGetInfo() {
        AiModelConfigVo vo = new AiModelConfigVo();
        when(service.queryById(3L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(3L).getData());
    }

    @Test
    @DisplayName("新增模型成功")
    void shouldAdd() {
        AiModelConfigBo bo = new AiModelConfigBo();
        when(service.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改模型成功")
    void shouldEdit() {
        AiModelConfigBo bo = new AiModelConfigBo();
        when(service.updateByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("复制模型返回新配置")
    void shouldCopy() {
        AiModelConfigVo vo = new AiModelConfigVo();
        when(service.copyById(5L)).thenReturn(vo);
        assertSame(vo, controller.copy(5L).getData());
    }

    @Test
    @DisplayName("删除模型成功")
    void shouldRemove() {
        when(service.deleteWithValidByIds(List.of(7L), true)).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{7L})));
    }

    @Test
    @DisplayName("连通性测试返回测试结果")
    void shouldTest() {
        AiModelConfigBo bo = new AiModelConfigBo();
        AiModelTestVo vo = new AiModelTestVo();
        when(service.testConnection(bo)).thenReturn(vo);
        assertSame(vo, controller.test(bo).getData());
    }

    @Test
    @DisplayName("删除失败返回失败码")
    void shouldRemoveFail() {
        when(service.deleteWithValidByIds(List.of(7L), true)).thenReturn(false);
        assertFalse(R.isSuccess(controller.remove(new Long[]{7L})));
    }

}