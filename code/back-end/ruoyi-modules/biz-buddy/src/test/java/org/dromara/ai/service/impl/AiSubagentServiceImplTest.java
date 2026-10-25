package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiAgentSubagent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiSubagentBo;
import org.dromara.ai.domain.vo.AiSubagentOptionVo;
import org.dromara.ai.domain.vo.AiSubagentVo;
import org.dromara.ai.mapper.AiAgentSubagentMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.mapper.AiSubagentMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AiSubagentServiceImpl} 单元测试：覆盖查询装配、只读工具白名单校验与绑定关系维护。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 子智能体 Service 单元测试")
class AiSubagentServiceImplTest {

    @Mock
    private AiSubagentMapper baseMapper;

    @Mock
    private AiAgentSubagentMapper agentSubagentMapper;

    @Mock
    private AiToolMapper toolMapper;

    @Mock
    private AiModelConfigMapper modelConfigMapper;

    @Mock
    private UserService userService;

    @InjectMocks
    private AiSubagentServiceImpl subagentService;

    /**
     * 复用共享的最小 Spring 容器，供 MapstructUtils / JsonUtils 静态初始化使用。
     */
    @BeforeAll
    static void initStaticContext() {
        AiServiceTestContext.initialize();
    }

    /**
     * 验证分页查询补齐工具显示名、模型名称与创建人昵称。
     */
    @Test
    @DisplayName("分页查询补齐展示字段")
    void shouldFillDisplayFieldsOnPage() {
        AiSubagentVo vo = new AiSubagentVo();
        vo.setId(1L);
        vo.setToolCodes("[\"read_x\"]");
        vo.setModelId(5L);
        vo.setCreateBy(7L);
        Page<AiSubagentVo> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(vo));
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);
        when(toolMapper.selectList(any())).thenReturn(List.of(tool("read_x", "只读工具", AiTool.TYPE_READ_ONLY)));
        AiModelConfig model = new AiModelConfig();
        model.setId(5L);
        model.setModelCode("gpt");
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(model));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(7L, "张三"));

        PageResult<AiSubagentVo> result = subagentService.queryPageList(new AiSubagentBo(), new PageQuery());

        assertEquals(1, result.getRows().size());
        assertEquals(List.of("只读工具"), vo.getToolNames());
        assertEquals("gpt", vo.getModelName());
        assertEquals("张三", vo.getCreateByName());
    }

    /**
     * 验证按主键查询不存在时抛业务异常。
     */
    @Test
    @DisplayName("查询不存在的主键抛异常")
    void shouldThrowWhenQueryByIdMissing() {
        when(baseMapper.selectVoById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> subagentService.queryById(1L));
    }

    /**
     * 验证查询详情时非法工具 JSON 落为空列表而不阻断。
     */
    @Test
    @DisplayName("非法工具 JSON 解析为空列表")
    void shouldTolerateInvalidToolJson() {
        AiSubagentVo vo = new AiSubagentVo();
        vo.setId(1L);
        vo.setToolCodes("not-a-json");
        when(baseMapper.selectVoById(1L)).thenReturn(vo);

        AiSubagentVo result = subagentService.queryById(1L);

        assertNotNull(result);
        assertTrue(result.getToolCodeList().isEmpty());
    }

    /**
     * 验证新增成功：规范名称、默认值填充与只读工具白名单序列化。
     */
    @Test
    @DisplayName("新增成功并写入只读工具白名单")
    void shouldInsertWithReadOnlyTools() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setName(" My_Agent ");
        bo.setDescription(" 说明 ");
        bo.setToolCodeList(List.of("read_x"));
        AiSubagent add = new AiSubagent();
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(toolMapper.selectOne(any())).thenReturn(tool("read_x", "只读工具", AiTool.TYPE_READ_ONLY));
        when(baseMapper.insert(any(AiSubagent.class))).thenAnswer(inv -> {
            ((AiSubagent) inv.getArgument(0)).setId(100L);
            return 1;
        });

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(add);

            assertTrue(subagentService.insertByBo(bo));
        }

        assertEquals(100L, bo.getId());
        assertEquals("my_agent", add.getName());
        assertEquals("说明", add.getDescription());
        assertEquals(AiSubagent.WORKSPACE_ISOLATED, add.getWorkspaceMode());
        assertEquals(AiSubagent.DEFAULT_STEPS, add.getSteps());
        assertEquals(AiSubagent.STATUS_ENABLED, add.getStatus());
        assertEquals("[\"read_x\"]", add.getToolCodes());
    }

    /**
     * 验证标识非法时抛业务异常。
     */
    @Test
    @DisplayName("标识非法时抛异常")
    void shouldRejectInvalidName() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setName("1bad");
        AiSubagent add = new AiSubagent();

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(add);
            assertThrows(ServiceException.class, () -> subagentService.insertByBo(bo));
        }
    }

    /**
     * 验证工作区模式非法时抛业务异常。
     */
    @Test
    @DisplayName("工作区模式非法时抛异常")
    void shouldRejectInvalidWorkspaceMode() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setName("my_agent");
        AiSubagent add = new AiSubagent();
        add.setWorkspaceMode("weird");

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(add);
            assertThrows(ServiceException.class, () -> subagentService.insertByBo(bo));
        }
    }

    /**
     * 验证写入类工具不允许下放给子智能体。
     */
    @Test
    @DisplayName("写入类工具被拒绝")
    void shouldRejectWriteTool() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setName("my_agent");
        bo.setToolCodeList(List.of("write_x"));
        AiSubagent add = new AiSubagent();
        when(toolMapper.selectOne(any())).thenReturn(tool("write_x", "写工具", "1"));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class, () -> subagentService.insertByBo(bo));
            assertTrue(ex.getMessage().contains("写入类工具"));
        }
    }

    /**
     * 验证未登记工具不允许加入白名单。
     */
    @Test
    @DisplayName("未登记工具被拒绝")
    void shouldRejectUnregisteredTool() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setName("my_agent");
        bo.setToolCodeList(List.of("missing"));
        AiSubagent add = new AiSubagent();
        when(toolMapper.selectOne(any())).thenReturn(null);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class, () -> subagentService.insertByBo(bo));
            assertTrue(ex.getMessage().contains("未在"));
        }
    }

    /**
     * 验证标识重复时抛业务异常。
     */
    @Test
    @DisplayName("标识重复时抛异常")
    void shouldRejectDuplicateName() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setName("my_agent");
        AiSubagent add = new AiSubagent();
        when(baseMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class, () -> subagentService.insertByBo(bo));
            assertTrue(ex.getMessage().contains("已存在"));
        }
    }

    /**
     * 验证修改时目标不存在抛业务异常。
     */
    @Test
    @DisplayName("修改目标不存在抛异常")
    void shouldThrowWhenUpdateTargetMissing() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> subagentService.updateByBo(bo));
    }

    /**
     * 验证修改时标识不允许变更。
     */
    @Test
    @DisplayName("修改不允许变更标识")
    void shouldRejectNameChangeOnUpdate() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setId(1L);
        bo.setName("new_name");
        AiSubagent exists = new AiSubagent();
        exists.setName("old_name");
        when(baseMapper.selectById(1L)).thenReturn(exists);

        ServiceException ex = assertThrows(ServiceException.class, () -> subagentService.updateByBo(bo));
        assertTrue(ex.getMessage().contains("不允许修改"));
    }

    /**
     * 验证修改成功更新实体。
     */
    @Test
    @DisplayName("修改成功")
    void shouldUpdateSuccessfully() {
        AiSubagentBo bo = new AiSubagentBo();
        bo.setId(1L);
        bo.setName("my_agent");
        AiSubagent exists = new AiSubagent();
        exists.setName("my_agent");
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiSubagent update = new AiSubagent();
        when(baseMapper.updateById(any(AiSubagent.class))).thenReturn(1);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiSubagent.class)).thenReturn(update);
            assertTrue(subagentService.updateByBo(bo));
        }
        assertEquals("my_agent", update.getName());
    }

    /**
     * 验证删除时未选择主键抛业务异常。
     */
    @Test
    @DisplayName("删除未选择主键抛异常")
    void shouldRejectEmptyDeleteIds() {
        assertThrows(ServiceException.class, () -> subagentService.deleteWithValidByIds(List.of()));
    }

    /**
     * 验证删除时部分主键不存在抛业务异常。
     */
    @Test
    @DisplayName("删除主键缺失抛异常")
    void shouldRejectDeleteIdsMismatch() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiSubagent()));
        assertThrows(ServiceException.class, () -> subagentService.deleteWithValidByIds(List.of(1L, 2L)));
    }

    /**
     * 验证被智能体绑定时不允许删除。
     */
    @Test
    @DisplayName("被绑定子智能体不允许删除")
    void shouldRejectDeleteWhenBound() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiSubagent()));
        when(agentSubagentMapper.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> subagentService.deleteWithValidByIds(List.of(1L)));
        assertTrue(ex.getMessage().contains("已被智能体绑定"));
    }

    /**
     * 验证删除成功执行物理删除。
     */
    @Test
    @DisplayName("删除成功")
    void shouldDeleteSuccessfully() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiSubagent()));
        when(agentSubagentMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.deleteByIds(any())).thenReturn(1);

        assertTrue(subagentService.deleteWithValidByIds(List.of(1L)));
    }

    /**
     * 验证下拉选项逐字段映射并解析工具白名单。
     */
    @Test
    @DisplayName("查询启用下拉选项")
    void shouldQueryOptions() {
        AiSubagent entity = new AiSubagent();
        entity.setId(1L);
        entity.setName("my_agent");
        entity.setDescription("说明");
        entity.setWorkspaceMode(AiSubagent.WORKSPACE_SHARED);
        entity.setSteps(5);
        entity.setToolCodes("[\"read_x\"]");
        when(baseMapper.selectList(any())).thenReturn(List.of(entity));

        List<AiSubagentOptionVo> options = subagentService.queryOptions();

        assertEquals(1, options.size());
        assertEquals(AiSubagent.WORKSPACE_SHARED, options.get(0).getWorkspaceMode());
        assertEquals(List.of("read_x"), options.get(0).getToolCodeList());
    }

    /**
     * 验证获取启用子智能体：空主键与不存在均抛异常，停用抛异常，启用正常返回。
     */
    @Test
    @DisplayName("获取启用子智能体")
    void shouldGetEnabledSubagent() {
        assertThrows(ServiceException.class, () -> subagentService.getEnabledSubagent(null));

        AiSubagent disabled = new AiSubagent();
        disabled.setName("my_agent");
        disabled.setStatus("1");
        when(baseMapper.selectById(2L)).thenReturn(disabled);
        assertThrows(ServiceException.class, () -> subagentService.getEnabledSubagent(2L));

        AiSubagent enabled = new AiSubagent();
        enabled.setStatus(AiSubagent.STATUS_ENABLED);
        when(baseMapper.selectById(3L)).thenReturn(enabled);
        assertSame(enabled, subagentService.getEnabledSubagent(3L));
    }

    /**
     * 验证查询已绑定且启用的子智能体时过滤停用 / 已删除项。
     */
    @Test
    @DisplayName("过滤停用或已删除的绑定子智能体")
    void shouldFilterBoundEnabledSubagents() {
        AiAgentSubagent bind1 = new AiAgentSubagent();
        bind1.setSubagentId(1L);
        AiAgentSubagent bind2 = new AiAgentSubagent();
        bind2.setSubagentId(2L);
        when(agentSubagentMapper.selectList(any())).thenReturn(List.of(bind1, bind2));
        AiSubagent enabled = new AiSubagent();
        enabled.setId(1L);
        enabled.setStatus(AiSubagent.STATUS_ENABLED);
        AiSubagent disabled = new AiSubagent();
        disabled.setId(2L);
        disabled.setStatus("1");
        when(baseMapper.selectByIds(any())).thenReturn(List.of(enabled, disabled));

        List<AiSubagent> result = subagentService.queryBoundEnabledSubagents(9L);

        assertEquals(1, result.size());
        assertSame(enabled, result.get(0));
    }

    /**
     * 验证无绑定主键时返回空列表。
     */
    @Test
    @DisplayName("无绑定返回空列表")
    void shouldReturnEmptyWhenNoBinding() {
        assertTrue(subagentService.queryBoundSubagentIds(null).isEmpty());
        when(agentSubagentMapper.selectList(any())).thenReturn(List.of());
        assertTrue(subagentService.queryBoundEnabledSubagents(9L).isEmpty());
    }

    /**
     * 验证批量查询智能体与子智能体绑定关系。
     */
    @Test
    @DisplayName("批量查询绑定关系")
    void shouldQueryAgentSubagentMap() {
        assertTrue(subagentService.queryAgentSubagentMap(List.of()).isEmpty());

        AiAgentSubagent a1 = new AiAgentSubagent();
        a1.setAgentId(1L);
        a1.setSubagentId(10L);
        AiAgentSubagent a2 = new AiAgentSubagent();
        a2.setAgentId(1L);
        a2.setSubagentId(11L);
        AiAgentSubagent a3 = new AiAgentSubagent();
        a3.setAgentId(2L);
        a3.setSubagentId(20L);
        when(agentSubagentMapper.selectList(any())).thenReturn(List.of(a1, a2, a3));

        Map<Long, List<Long>> result = subagentService.queryAgentSubagentMap(Set.of(1L, 2L));

        assertEquals(List.of(10L, 11L), result.get(1L));
        assertEquals(List.of(20L), result.get(2L));
    }

    /**
     * 验证替换绑定时先删后插并去重。
     */
    @Test
    @DisplayName("替换绑定先删后插并去重")
    void shouldReplaceBindings() {
        subagentService.replaceAgentBindings(1L, List.of(10L, 10L, 11L));

        verify(agentSubagentMapper).delete(any());
        verify(agentSubagentMapper, times(2)).insert(any(AiAgentSubagent.class));
    }

    /**
     * 验证清空绑定时只删除不插入。
     */
    @Test
    @DisplayName("空绑定只删除不插入")
    void shouldReplaceBindingsWithEmpty() {
        subagentService.replaceAgentBindings(1L, List.of());

        verify(agentSubagentMapper).delete(any());
        verify(agentSubagentMapper, never()).insert(any(AiAgentSubagent.class));
    }

    /**
     * 验证删除智能体绑定时对空集合短路。
     */
    @Test
    @DisplayName("删除绑定对空集合短路")
    void shouldSkipEmptyAgentBindings() {
        subagentService.deleteAgentBindings(List.of());
        subagentService.deleteAgentBindings(null);

        verifyNoInteractions(agentSubagentMapper);
    }

    private AiTool tool(String code, String name, String type) {
        AiTool tool = new AiTool();
        tool.setToolCode(code);
        tool.setToolName(name);
        tool.setToolType(type);
        return tool;
    }

}