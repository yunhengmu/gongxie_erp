package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiEntryAgentBo;
import org.dromara.ai.domain.bo.AiExpertAgentBo;
import org.dromara.ai.domain.bo.AiExpertPromptBo;
import org.dromara.ai.domain.vo.AiAgentToolTrustVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiEntryAgentVo;
import org.dromara.ai.domain.vo.AiExpertAgentVo;
import org.dromara.ai.domain.vo.AiExpertPromptVo;
import org.dromara.ai.domain.vo.AiExpertVo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiExpertPromptService;
import org.dromara.ai.service.IAiToolService;
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
 * {@link AiAgentController} 单元测试：入口 / 专家智能体定义与工具信任决策。
 */
@DisplayName("AI 智能体定义 Controller")
class AiAgentControllerTest {

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final IAiToolService toolService = mock(IAiToolService.class);

    private final IAiExpertPromptService promptService = mock(IAiExpertPromptService.class);

    private final AiAgentController controller = new AiAgentController(agentService, toolService, promptService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询入口智能体")
    void shouldEntry() {
        AiEntryAgentVo vo = new AiEntryAgentVo();
        when(agentService.queryEntryAgent()).thenReturn(vo);
        assertSame(vo, controller.entry().getData());
    }

    @Test
    @DisplayName("修改入口智能体成功")
    void shouldUpdateEntry() {
        AiEntryAgentBo bo = new AiEntryAgentBo();
        when(agentService.updateEntryAgent(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.updateEntry(bo)));
    }

    @Test
    @DisplayName("分页查询专家列表")
    void shouldList() {
        AiExpertAgentBo bo = new AiExpertAgentBo();
        PageQuery query = new PageQuery();
        PageResult<AiExpertAgentVo> page = PageResult.build();
        when(agentService.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("查询启用智能体选项")
    void shouldOptions() {
        List<AiAgentVo> options = List.of(new AiAgentVo());
        when(agentService.queryOptions()).thenReturn(options);
        assertSame(options, controller.options().getData());
    }

    @Test
    @DisplayName("查询自动候选专家与全部可见专家")
    void shouldQueryExperts() {
        List<AiExpertVo> candidates = List.of(new AiExpertVo());
        List<AiExpertVo> all = List.of(new AiExpertVo(), new AiExpertVo());
        when(agentService.queryExperts()).thenReturn(candidates);
        when(agentService.queryVisibleExperts()).thenReturn(all);
        assertSame(candidates, controller.expertCandidates().getData());
        assertSame(all, controller.experts().getData());
    }

    @Test
    @DisplayName("查询可绑定工具选项")
    void shouldToolOptions() {
        List<AiToolVo> options = List.of(new AiToolVo());
        when(toolService.queryOptions()).thenReturn(options);
        assertSame(options, controller.toolOptions().getData());
    }

    @Test
    @DisplayName("辅助生成专家提示词（仅返回建议值，不落库）")
    void shouldGenerateExpertPrompt() {
        AiExpertPromptBo bo = new AiExpertPromptBo();
        AiExpertPromptVo vo = new AiExpertPromptVo();
        when(promptService.generate(bo)).thenReturn(vo);
        assertSame(vo, controller.generateExpertPrompt(bo).getData());
    }

    @Test
    @DisplayName("获取智能体详情与已记住工具")
    void shouldInfoAndTrustedTools() {
        AiAgentVo vo = new AiAgentVo();
        List<AiAgentToolTrustVo> trusts = List.of(new AiAgentToolTrustVo());
        when(agentService.queryById(2L)).thenReturn(vo);
        when(agentService.queryTrustedTools(2L)).thenReturn(trusts);
        assertSame(vo, controller.getInfo(2L).getData());
        assertSame(trusts, controller.trustedTools(2L).getData());
    }

    @Test
    @DisplayName("撤销工具信任决策成功")
    void shouldRevokeTrustedTool() {
        when(agentService.revokeTrustedTool(2L, 8L, "code")).thenReturn(true);
        assertTrue(R.isSuccess(controller.revokeTrustedTool(2L, 8L, "code")));
    }

    @Test
    @DisplayName("新增专家成功")
    void shouldAdd() {
        AiExpertAgentBo bo = new AiExpertAgentBo();
        when(agentService.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("修改专家失败返回失败码")
    void shouldEditFail() {
        AiExpertAgentBo bo = new AiExpertAgentBo();
        when(agentService.updateByBo(bo)).thenReturn(false);
        assertFalse(R.isSuccess(controller.edit(bo)));
    }

    @Test
    @DisplayName("删除专家成功")
    void shouldRemove() {
        when(agentService.deleteWithValidByIds(List.of(1L, 2L), true)).thenReturn(true);
        assertTrue(R.isSuccess(controller.remove(new Long[]{1L, 2L})));
    }

}