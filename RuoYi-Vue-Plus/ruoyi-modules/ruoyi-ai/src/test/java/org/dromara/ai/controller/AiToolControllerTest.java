package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiToolBo;
import org.dromara.ai.domain.bo.AiToolTagBatchBo;
import org.dromara.ai.domain.bo.AiToolTagBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.ai.service.IAiToolService;
import org.dromara.ai.service.IAiToolTagService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiToolController} 单元测试：业务工具注册与工具标签管理。
 */
@DisplayName("AI 业务工具 Controller")
class AiToolControllerTest {

    private final IAiToolService toolService = mock(IAiToolService.class);

    private final IAiToolTagService tagService = mock(IAiToolTagService.class);

    private final AiToolController controller = new AiToolController(toolService, tagService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询工具列表")
    void shouldList() {
        AiToolBo bo = new AiToolBo();
        PageQuery query = new PageQuery();
        PageResult<AiToolVo> page = PageResult.build();
        when(toolService.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("获取工具详情")
    void shouldGetInfo() {
        AiToolVo vo = new AiToolVo();
        when(toolService.queryById(2L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(2L).getData());
    }

    @Test
    @DisplayName("新增与修改工具注册")
    void shouldAddAndEdit() {
        AiToolBo bo = new AiToolBo();
        when(toolService.insertByBo(bo)).thenReturn(true);
        when(toolService.updateByBo(bo)).thenReturn(false);
        assertTrue(R.isSuccess(controller.add(bo)));
        assertFalse(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("批量删除工具注册")
    void shouldRemove() {
        when(toolService.deleteWithValidByIds(List.of(1L), true)).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{1L})));
    }

    @Test
    @DisplayName("查询标签列表与选项")
    void shouldTagListAndOptions() {
        AiToolTagBo bo = new AiToolTagBo();
        PageQuery query = new PageQuery();
        PageResult<AiToolTagVo> page = PageResult.build();
        List<AiToolTagVo> options = List.of(new AiToolTagVo());
        when(tagService.queryPageList(bo, query)).thenReturn(page);
        when(tagService.queryOptions()).thenReturn(options);
        assertSame(page, controller.tagList(bo, query).getData());
        assertSame(options, controller.tagOptions().getData());
    }

    @Test
    @DisplayName("新增与修改标签")
    void shouldTagAddAndEdit() {
        AiToolTagBo bo = new AiToolTagBo();
        when(tagService.insertByBo(bo)).thenReturn(true);
        when(tagService.updateByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.tagAdd(bo)));
        assertTrue(R.isSuccess(controller.tagEdit(bo)));
    }

    @Test
    @DisplayName("批量打标签与移除标签")
    void shouldTagApplyAndRemoveBatch() {
        AiToolTagBatchBo bo = new AiToolTagBatchBo();
        bo.setToolIds(List.of(1L, 2L));
        bo.setTagIds(List.of(9L));
        assertTrue(R.isSuccess(controller.tagApplyBatch(bo)));
        assertTrue(R.isSuccess(controller.tagRemoveBatch(bo)));
        verify(tagService).applyTagsToTools(List.of(1L, 2L), List.of(9L));
        verify(tagService).removeTagsFromTools(List.of(1L, 2L), List.of(9L));
    }

    @Test
    @DisplayName("批量删除标签")
    void shouldTagRemove() {
        when(tagService.deleteWithValidByIds(List.of(9L))).thenReturn(false);
        assertFalse(R.isSuccess(controller.tagRemove(new Long[]{9L})));
    }

}