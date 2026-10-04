package org.dromara.ai.controller;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceContentBo;
import org.dromara.ai.domain.vo.AiWorkspaceContentVo;
import org.dromara.ai.domain.vo.AiWorkspaceFileVo;
import org.dromara.ai.service.IAiWorkspaceService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.R;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiWorkspaceController} 单元测试：工作区配置、文件清单与分层内容读写。
 */
@DisplayName("AI 工作区 Controller")
class AiWorkspaceControllerTest {

    private final IAiWorkspaceService workspaceService = mock(IAiWorkspaceService.class);

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final AiWorkspaceController controller = new AiWorkspaceController(workspaceService, store);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询工作区上下文配置")
    void shouldGetConfig() {
        AiWorkspaceConfigBo bo = new AiWorkspaceConfigBo();
        when(store.workspace()).thenReturn(bo);
        assertSame(bo, controller.getConfig().getData());
    }

    @Test
    @DisplayName("保存工作区上下文配置")
    void shouldUpdateConfig() {
        AiWorkspaceConfigBo bo = new AiWorkspaceConfigBo();
        assertTrue(R.isSuccess(controller.updateConfig(bo)));
        verify(store).updateWorkspace(bo);
    }

    @Test
    @DisplayName("查询工作区文件清单")
    void shouldListFiles() {
        List<AiWorkspaceFileVo> files = List.of(new AiWorkspaceFileVo());
        when(workspaceService.listFiles(1L, 2L)).thenReturn(files);
        assertSame(files, controller.listFiles(1L, 2L).getData());
    }

    @Test
    @DisplayName("读取指定层文件内容")
    void shouldReadContent() {
        AiWorkspaceContentVo vo = new AiWorkspaceContentVo();
        when(workspaceService.readContent(1L, 2L, "AGENTS.md", "USER")).thenReturn(vo);
        assertSame(vo, controller.readContent(1L, 2L, "AGENTS.md", "USER").getData());
    }

    @Test
    @DisplayName("保存文件内容与清除用户覆盖")
    void shouldWriteAndReset() {
        AiWorkspaceContentBo bo = new AiWorkspaceContentBo();
        assertTrue(R.isSuccess(controller.writeContent(bo)));
        assertTrue(R.isSuccess(controller.resetUserOverride(1L, 2L, "AGENTS.md")));
        verify(workspaceService).writeContent(bo);
        verify(workspaceService).resetUserOverride(1L, 2L, "AGENTS.md");
    }

}