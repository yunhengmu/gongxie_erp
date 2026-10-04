package org.dromara.ai.controller;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiMcpExposedBo;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.vo.AiMcpServerToolVo;
import org.dromara.ai.domain.vo.AiMcpServerVo;
import org.dromara.ai.service.IAiMcpServerService;
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
 * {@link AiMcpServerController} 单元测试：MCP 出口配置与暴露清单。
 */
@DisplayName("MCP 服务端 Controller")
class AiMcpServerControllerTest {

    private final IAiMcpServerService service = mock(IAiMcpServerService.class);

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final AiMcpServerController controller = new AiMcpServerController(service, store);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询出口状态")
    void shouldGetServer() {
        AiMcpServerVo vo = new AiMcpServerVo();
        when(service.queryServer()).thenReturn(vo);
        assertSame(vo, controller.getServer().getData());
    }

    @Test
    @DisplayName("保存出口访问配置转发到配置中心")
    void shouldUpdateServer() {
        AiMcpServerConfigBo bo = new AiMcpServerConfigBo();
        assertTrue(R.isSuccess(controller.updateServer(bo)));
        verify(store).updateMcpServer(bo);
    }

    @Test
    @DisplayName("查询出口候选工具清单")
    void shouldListTools() {
        List<AiMcpServerToolVo> tools = List.of(new AiMcpServerToolVo());
        when(service.queryTools()).thenReturn(tools);
        assertSame(tools, controller.listTools().getData());
    }

    @Test
    @DisplayName("保存出口暴露清单")
    void shouldUpdateTools() {
        AiMcpExposedBo bo = new AiMcpExposedBo();
        bo.setToolCodes(List.of("a", "b"));
        assertTrue(R.isSuccess(controller.updateTools(bo)));
        verify(service).updateExposed(List.of("a", "b"));
    }

}