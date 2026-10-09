package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiKnowledgeBaseBo;
import org.dromara.ai.domain.vo.AiKnowledgeBaseVo;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link AiKnowledgeBaseController} 单元测试：知识库 CRUD 与可见选项。
 */
@DisplayName("AI 知识库 Controller")
class AiKnowledgeBaseControllerTest {

    private final IAiKnowledgeBaseService service = mock(IAiKnowledgeBaseService.class);

    private final AiKnowledgeBaseController controller = new AiKnowledgeBaseController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询知识库列表")
    void shouldList() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        PageQuery query = new PageQuery();
        PageResult<AiKnowledgeBaseVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询知识库选项")
    void shouldOptions() {
        List<AiKnowledgeBaseVo> options = List.of(new AiKnowledgeBaseVo());
        when(service.queryOptions()).thenReturn(options);
        assertSame(options, controller.options().getData());
    }

    @Test
    @DisplayName("查询当前用户可见知识库选项")
    void shouldVisibleOptions() {
        List<AiKnowledgeBaseVo> options = List.of(new AiKnowledgeBaseVo());
        when(service.queryVisibleOptions(1L)).thenReturn(options);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(options, controller.visibleOptions().getData());
        }
    }

    @Test
    @DisplayName("获取知识库详情")
    void shouldGetInfo() {
        AiKnowledgeBaseVo vo = new AiKnowledgeBaseVo();
        when(service.queryById(2L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(2L).getData());
    }

    @Test
    @DisplayName("新增知识库成功")
    void shouldAdd() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        when(service.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改知识库失败返回失败码")
    void shouldEditFail() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        when(service.updateByBo(bo)).thenReturn(false);
        assertFalse(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("批量删除知识库")
    void shouldRemove() {
        when(service.deleteWithValidByIds(List.of(1L, 2L), true)).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{1L, 2L})));
    }

}