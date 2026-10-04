package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiSkillBo;
import org.dromara.ai.domain.vo.AiSkillOptionVo;
import org.dromara.ai.domain.vo.AiSkillVo;
import org.dromara.ai.service.IAiSkillService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiSkillController} 单元测试：技能仓库 CRUD 与可见选项。
 */
@DisplayName("AI 技能仓库 Controller")
class AiSkillControllerTest {

    private final IAiSkillService service = mock(IAiSkillService.class);

    private final AiSkillController controller = new AiSkillController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询技能列表")
    void shouldList() {
        AiSkillBo bo = new AiSkillBo();
        PageQuery query = new PageQuery();
        PageResult<AiSkillVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        R<PageResult<AiSkillVo>> r = controller.list(bo, query);
        assertTrue(R.isSuccess(r));
        assertSame(page, r.getData());
    }

    @Test
    @DisplayName("查询可见技能选项使用当前用户")
    void shouldVisibleOptions() {
        List<AiSkillOptionVo> options = List.of(new AiSkillOptionVo());
        when(service.queryVisibleOptions(9L)).thenReturn(options);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);
            R<List<AiSkillOptionVo>> r = controller.visibleOptions();
            assertTrue(R.isSuccess(r));
            assertSame(options, r.getData());
        }
    }

    @Test
    @DisplayName("获取技能详情")
    void shouldGetInfo() {
        AiSkillVo vo = new AiSkillVo();
        when(service.queryById(1L)).thenReturn(vo);
        R<AiSkillVo> r = controller.getInfo(1L);
        assertSame(vo, r.getData());
    }

    @Test
    @DisplayName("新增技能成功")
    void shouldAdd() {
        AiSkillBo bo = new AiSkillBo();
        when(service.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改技能失败返回失败码")
    void shouldEditFail() {
        AiSkillBo bo = new AiSkillBo();
        when(service.updateByBo(bo)).thenReturn(false);
        assertFalse(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("批量删除技能")
    void shouldRemove() {
        when(service.deleteWithValidByIds(List.of(1L, 2L))).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{1L, 2L})));
        verify(service).deleteWithValidByIds(List.of(1L, 2L));
    }

}