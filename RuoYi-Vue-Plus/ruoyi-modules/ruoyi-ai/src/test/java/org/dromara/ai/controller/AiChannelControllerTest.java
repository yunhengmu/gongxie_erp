package org.dromara.ai.controller;

import org.dromara.ai.core.channel.AiChannelRuntimeRegistry;
import org.dromara.ai.domain.bo.AiChannelBo;
import org.dromara.ai.domain.bo.AiChannelIdentityBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiChannelIdentityVo;
import org.dromara.ai.domain.vo.AiChannelThreadVo;
import org.dromara.ai.domain.vo.AiChannelVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChannelService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelController} 单元测试：渠道配置、外部身份映射与线程映射管理。
 */
@DisplayName("AI 渠道接入 Controller")
class AiChannelControllerTest {

    private final IAiChannelService channelService = mock(IAiChannelService.class);

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final AiChannelRuntimeRegistry runtimeRegistry = mock(AiChannelRuntimeRegistry.class);

    private final AiChannelController controller =
        new AiChannelController(channelService, agentService, runtimeRegistry);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询渠道列表")
    void shouldList() {
        AiChannelBo bo = new AiChannelBo();
        PageQuery query = new PageQuery();
        PageResult<AiChannelVo> page = PageResult.build();
        when(channelService.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询启用智能体选项")
    void shouldAgentOptions() {
        List<AiAgentVo> options = List.of(new AiAgentVo());
        when(agentService.queryOptions()).thenReturn(options);
        assertSame(options, controller.agentOptions().getData());
    }

    @Test
    @DisplayName("获取渠道详情")
    void shouldGetInfo() {
        AiChannelVo vo = new AiChannelVo();
        when(channelService.queryById(2L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(2L).getData());
    }

    @Test
    @DisplayName("新增渠道成功后刷新运行期装配")
    void shouldAddAndRefresh() {
        AiChannelBo bo = new AiChannelBo();
        bo.setChannelCode("ch1");
        when(channelService.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
        verify(runtimeRegistry).refresh("ch1");
    }

    @Test
    @DisplayName("修改渠道失败不刷新装配")
    void shouldEditFailWithoutRefresh() {
        AiChannelBo bo = new AiChannelBo();
        bo.setChannelCode("ch1");
        when(channelService.updateByBo(bo)).thenReturn(false);
        assertFalse(R.isSuccess(controller.edit(bo)));
        verify(runtimeRegistry, never()).refresh("ch1");
    }

    @Test
    @DisplayName("删除渠道成功后驱逐运行期连接")
    void shouldRemoveAndEvict() {
        AiChannelVo vo = new AiChannelVo();
        vo.setChannelCode("ch9");
        when(channelService.queryById(9L)).thenReturn(vo);
        when(channelService.deleteWithValidByIds(List.of(9L))).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{9L})));
        verify(runtimeRegistry).evict("ch9");
    }

    @Test
    @DisplayName("查询外部身份映射列表")
    void shouldIdentityList() {
        AiChannelIdentityBo bo = new AiChannelIdentityBo();
        PageQuery query = new PageQuery();
        PageResult<AiChannelIdentityVo> page = PageResult.build();
        when(channelService.queryIdentityPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.identityList(bo, query).getData());
    }

    @Test
    @DisplayName("新增与修改外部身份映射")
    void shouldIdentityAddAndEdit() {
        AiChannelIdentityBo bo = new AiChannelIdentityBo();
        when(channelService.insertIdentity(bo)).thenReturn(true);
        when(channelService.updateIdentity(bo)).thenReturn(false);
        assertTrue(R.isSuccess(controller.identityAdd(bo)));
        assertFalse(R.isSuccess(controller.identityEdit(bo)));
    }

    @Test
    @DisplayName("删除外部身份映射")
    void shouldIdentityRemove() {
        when(channelService.deleteIdentities(List.of(1L))).thenReturn(true);
        assertTrue(R.isSuccess(controller.identityRemove(new Long[]{1L})));
    }

    @Test
    @DisplayName("查询线程映射列表与删除")
    void shouldThreadListAndRemove() {
        PageQuery query = new PageQuery();
        PageResult<AiChannelThreadVo> page = PageResult.build();
        when(channelService.queryThreadPageList(5L, query)).thenReturn(page);
        when(channelService.deleteThreads(List.of(1L, 2L))).thenReturn(true);
        assertSame(page, controller.threadList(5L, query).getData());
        assertTrue(R.isSuccess(controller.threadRemove(new Long[]{1L, 2L})));
    }

}