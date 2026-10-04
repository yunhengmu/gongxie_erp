package org.dromara.ai.controller;

import io.agentscope.core.permission.PermissionMode;
import org.dromara.ai.core.agent.AiPermissionModeStore;
import org.dromara.ai.domain.bo.AiPermissionDecisionBo;
import org.dromara.ai.domain.vo.AiPermissionDecisionVo;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiPermissionController} 单元测试：权限决策日志查询与全局模式切换。
 */
@DisplayName("AI 工具权限 Controller")
class AiPermissionControllerTest {

    private final IAiPermissionDecisionService decisionService = mock(IAiPermissionDecisionService.class);

    private final AiPermissionModeStore modeStore = mock(AiPermissionModeStore.class);

    private final AiPermissionController controller = new AiPermissionController(decisionService, modeStore);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询权限决策日志")
    void shouldDecisionList() {
        AiPermissionDecisionBo bo = new AiPermissionDecisionBo();
        PageQuery query = new PageQuery();
        PageResult<AiPermissionDecisionVo> page = PageResult.build();
        when(decisionService.queryPage(bo, query)).thenReturn(page);
        assertSame(page, controller.decisionList(bo, query).getData());
    }

    @Test
    @DisplayName("查询当前全局权限模式：模式名落在 data（前端读 res.data）")
    void shouldGetMode() {
        when(modeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        R<String> r = controller.getMode();
        // 回归守卫：`R.ok(String)` 是「自定义提示信息」重载，会把模式名放进 msg，
        // 而前端 loadMode() 读的是 res.data（decision/index.vue），故必须用 R.data 承载模式名。
        assertEquals("DEFAULT", r.getData());
        assertEquals("操作成功", r.getMsg());
    }

    @Test
    @DisplayName("切换全局权限模式")
    void shouldUpdateMode() {
        assertTrue(R.isSuccess(controller.updateMode("BYPASS")));
        verify(modeStore).update("BYPASS");
    }

}