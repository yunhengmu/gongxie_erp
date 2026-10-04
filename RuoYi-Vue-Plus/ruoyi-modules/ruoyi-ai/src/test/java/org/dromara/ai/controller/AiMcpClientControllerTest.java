package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiMcpClientBo;
import org.dromara.ai.domain.vo.AiMcpClientTestVo;
import org.dromara.ai.domain.vo.AiMcpClientVo;
import org.dromara.ai.service.IAiMcpClientService;
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
 * {@link AiMcpClientController} 单元测试：外部 MCP 连接 CRUD 与连通性测试。
 */
@DisplayName("MCP 客户端 Controller")
class AiMcpClientControllerTest {

    private final IAiMcpClientService service = mock(IAiMcpClientService.class);

    private final AiMcpClientController controller = new AiMcpClientController(service);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询外部 MCP 连接")
    void shouldList() {
        AiMcpClientBo bo = new AiMcpClientBo();
        PageQuery query = new PageQuery();
        PageResult<AiMcpClientVo> page = PageResult.build();
        when(service.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("获取外部 MCP 连接详情")
    void shouldGetInfo() {
        AiMcpClientVo vo = new AiMcpClientVo();
        when(service.queryById(2L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(2L).getData());
    }

    @Test
    @DisplayName("新增外部 MCP 连接成功")
    void shouldAdd() {
        AiMcpClientBo bo = new AiMcpClientBo();
        when(service.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改外部 MCP 连接成功")
    void shouldEdit() {
        AiMcpClientBo bo = new AiMcpClientBo();
        when(service.updateByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("删除外部 MCP 连接失败返回失败码")
    void shouldRemoveFail() {
        when(service.deleteWithValidByIds(List.of(1L), true)).thenReturn(false);
        assertFalse(R.isSuccess(controller.remove(new Long[]{1L})));
    }

    @Test
    @DisplayName("测试连接返回工具清单")
    void shouldTest() {
        AiMcpClientBo bo = new AiMcpClientBo();
        AiMcpClientTestVo vo = new AiMcpClientTestVo();
        when(service.testConnection(bo)).thenReturn(vo);
        assertSame(vo, controller.test(bo).getData());
    }

}