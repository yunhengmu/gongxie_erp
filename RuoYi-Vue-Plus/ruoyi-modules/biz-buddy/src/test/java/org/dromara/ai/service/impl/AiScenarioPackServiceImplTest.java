package org.dromara.ai.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.AiScenarioPackSkill;
import org.dromara.ai.domain.AiScenarioPackTool;
import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiScenarioPackBo;
import org.dromara.ai.domain.bo.AiScenarioPackMcpToolBo;
import org.dromara.ai.domain.bo.AiScenarioPackToolBo;
import org.dromara.ai.domain.vo.AiScenarioPackMcpOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.mapper.AiScenarioPackMapper;
import org.dromara.ai.mapper.AiScenarioPackMcpToolMapper;
import org.dromara.ai.mapper.AiScenarioPackSkillMapper;
import org.dromara.ai.mapper.AiScenarioPackToolMapper;
import org.dromara.ai.mapper.AiSkillMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticApplicationContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiScenarioPackServiceImpl} 单元测试：查询装配、绑定规范化与增删改校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("场景包 Service")
class AiScenarioPackServiceImplTest {

    @Mock
    private AiScenarioPackMapper baseMapper;
    @Mock
    private AiScenarioPackToolMapper packToolMapper;
    @Mock
    private AiScenarioPackMcpToolMapper packMcpToolMapper;
    @Mock
    private AiScenarioPackSkillMapper packSkillMapper;
    @Mock
    private AiToolMapper toolMapper;
    @Mock
    private AiSkillMapper skillMapper;
    @Mock
    private AiMcpClientMapper mcpClientMapper;
    @Mock
    private IAiToolService toolService;
    @Mock
    private IAiSkillService skillService;
    @Mock
    private UserService userService;
    @Mock
    private DeptService deptService;

    @InjectMocks
    private AiScenarioPackServiceImpl service;

    @BeforeAll
    static void setUp() {
        // 无 Spring 容器时，初始化 MapstructUtils 依赖的 Converter 与实体元数据
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.refresh();
        new SpringUtil().setApplicationContext(context);
        for (Class<?> clazz : new Class<?>[]{AiScenarioPack.class, AiScenarioPackTool.class,
            AiScenarioPackMcpTool.class, AiScenarioPackSkill.class, AiMcpClient.class, AiTool.class,
            AiSkill.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
        }
    }

    @Test
    @DisplayName("分页查询填充部门名/昵称与空绑定")
    void shouldQueryPageList() {
        AiScenarioPackVo vo = new AiScenarioPackVo();
        vo.setId(1L);
        vo.setOwnerDeptId(2L);
        vo.setCreateBy(3L);
        Page<AiScenarioPackVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        doReturn(page).when(baseMapper).selectVoPage(any(), any());
        when(deptService.selectDeptNamesByIds(any())).thenReturn(Map.of(2L, "研发部"));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(3L, "张三"));
        when(packToolMapper.selectList(any())).thenReturn(List.of());
        when(packMcpToolMapper.selectList(any())).thenReturn(List.of());
        when(packSkillMapper.selectList(any())).thenReturn(List.of());

        PageResult<AiScenarioPackVo> result = service.queryPageList(new AiScenarioPackBo(), new PageQuery(10, 1));

        assertEquals(1, result.getTotal());
        Collection<AiScenarioPackVo> rows = result.getRows();
        assertEquals(1, rows.size());
        AiScenarioPackVo filled = rows.iterator().next();
        assertEquals("研发部", filled.getOwnerDeptName());
        assertEquals("张三", filled.getCreateByName());
        assertTrue(filled.getToolBindings().isEmpty());
    }

    @Test
    @DisplayName("分页查询空记录直接返回")
    void shouldQueryPageListWhenEmpty() {
        Page<AiScenarioPackVo> page = new Page<>(1, 10);
        page.setRecords(List.of());
        doReturn(page).when(baseMapper).selectVoPage(any(), any());

        assertTrue(service.queryPageList(new AiScenarioPackBo(), new PageQuery(10, 1)).getRows().isEmpty());
    }

    @Test
    @DisplayName("详情查询：命中返回、缺失抛异常")
    void shouldQueryById() {
        AiScenarioPackVo vo = new AiScenarioPackVo();
        vo.setId(1L);
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        when(packToolMapper.selectList(any())).thenReturn(List.of());
        when(packMcpToolMapper.selectList(any())).thenReturn(List.of());
        when(packSkillMapper.selectList(any())).thenReturn(List.of());

        assertEquals(1L, service.queryById(1L).getId().longValue());

        when(baseMapper.selectVoById(2L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.queryById(2L));
    }

    @Test
    @DisplayName("新增：保存三类绑定")
    void shouldInsertWithBindings() {
        AiScenarioPackBo bo = packBo("demo");
        AiScenarioPackToolBo toolBo = new AiScenarioPackToolBo();
        toolBo.setToolCode("t1");
        bo.setToolBindings(List.of(toolBo));
        AiScenarioPackMcpToolBo mcpBo = new AiScenarioPackMcpToolBo();
        mcpBo.setMcpClientId(5L);
        mcpBo.setToolName("m1");
        bo.setMcpToolBindings(List.of(mcpBo));
        bo.setSkillIds(List.of(7L));

        AiMcpClient client = new AiMcpClient();
        client.setStatus(AiMcpClient.STATUS_ENABLED);
        client.setEnableTools("m1,m2");

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(new AiScenarioPack());
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiScenarioPack.class))).thenReturn(1);
            when(toolService.exists("t1")).thenReturn(true);
            when(mcpClientMapper.selectById(5L)).thenReturn(client);
            when(skillService.getEnabledSkill(7L)).thenReturn(new AiSkill());
            when(packToolMapper.insert(any(AiScenarioPackTool.class))).thenReturn(1);
            when(packMcpToolMapper.insert(any(AiScenarioPackMcpTool.class))).thenReturn(1);
            when(packSkillMapper.insert(any(AiScenarioPackSkill.class))).thenReturn(1);

            assertTrue(service.insertByBo(bo));
        }
        verify(packToolMapper, times(1)).insert(any(AiScenarioPackTool.class));
        verify(packMcpToolMapper, times(1)).insert(any(AiScenarioPackMcpTool.class));
        verify(packSkillMapper, times(1)).insert(any(AiScenarioPackSkill.class));
    }

    @Test
    @DisplayName("新增：编码非法/重复时拒绝")
    void shouldRejectInvalidCodeOnInsert() {
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(new AiScenarioPack());

            assertThrows(ServiceException.class, () -> service.insertByBo(packBo("1bad")));

            when(baseMapper.selectCount(any())).thenReturn(1L);
            assertThrows(ServiceException.class, () -> service.insertByBo(packBo("demo")));
        }
    }

    @Test
    @DisplayName("新增：生效范围非法时拒绝")
    void shouldRejectInvalidScopeOnInsert() {
        AiScenarioPackBo bo = packBo("demo");
        bo.setVisibleScope("9");
        AiScenarioPack entity = new AiScenarioPack();
        entity.setVisibleScope("9");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(entity);
            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("新增：工具未登记/MCP 连接非法时拒绝")
    void shouldRejectInvalidBindingsOnInsert() {
        AiScenarioPackBo bo = packBo("demo");
        AiScenarioPackToolBo toolBo = new AiScenarioPackToolBo();
        toolBo.setToolCode("ghost");
        bo.setToolBindings(List.of(toolBo));
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(new AiScenarioPack());
            when(toolService.exists("ghost")).thenReturn(false);
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiScenarioPack.class))).thenReturn(1);
            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }

        AiScenarioPackBo mcpBo = packBo("demo");
        AiScenarioPackMcpToolBo binding = new AiScenarioPackMcpToolBo();
        binding.setMcpClientId(5L);
        binding.setToolName("m1");
        mcpBo.setMcpToolBindings(List.of(binding));
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(new AiScenarioPack());
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiScenarioPack.class))).thenReturn(1);
            when(mcpClientMapper.selectById(5L)).thenReturn(null);
            assertThrows(ServiceException.class, () -> service.insertByBo(mcpBo));

            AiMcpClient noWhitelist = new AiMcpClient();
            noWhitelist.setStatus(AiMcpClient.STATUS_ENABLED);
            noWhitelist.setEnableTools(" ");
            when(mcpClientMapper.selectById(5L)).thenReturn(noWhitelist);
            assertThrows(ServiceException.class, () -> service.insertByBo(mcpBo));

            AiMcpClient limited = new AiMcpClient();
            limited.setStatus(AiMcpClient.STATUS_ENABLED);
            limited.setEnableTools("other");
            when(mcpClientMapper.selectById(5L)).thenReturn(limited);
            assertThrows(ServiceException.class, () -> service.insertByBo(mcpBo));
        }
    }

    @Test
    @DisplayName("修改：不存在或编码变更时拒绝")
    void shouldRejectUpdateWhenMissingOrCodeChanged() {
        AiScenarioPackBo bo = packBo("demo");
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));

        AiScenarioPack exists = new AiScenarioPack();
        exists.setId(1L);
        exists.setPackCode("demo");
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiScenarioPackBo changed = packBo("other");
        changed.setId(1L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(new AiScenarioPack());
            assertThrows(ServiceException.class, () -> service.updateByBo(changed));
        }
    }

    @Test
    @DisplayName("修改：编码一致时保存绑定")
    void shouldUpdateSuccessfully() {
        AiScenarioPack exists = new AiScenarioPack();
        exists.setId(1L);
        exists.setPackCode("demo");
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiScenarioPackBo bo = packBo("demo");
        bo.setId(1L);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiScenarioPackBo.class), eq(AiScenarioPack.class)))
                .thenReturn(new AiScenarioPack());
            when(baseMapper.updateById(any(AiScenarioPack.class))).thenReturn(1);
            assertTrue(service.updateByBo(bo));
        }
        verify(packToolMapper, times(1)).delete(any());
    }

    @Test
    @DisplayName("删除：空参/不存在拒绝，正常删除清理绑定")
    void shouldDeleteWithValidation() {
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of()));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiScenarioPack()));
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L, 2L)));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiScenarioPack(), new AiScenarioPack()));
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteWithValidByIds(List.of(1L, 2L)));
        verify(packToolMapper, times(2)).delete(any());
    }

    @Test
    @DisplayName("选项查询：空列表与计数填充")
    void shouldQueryOptions() {
        when(baseMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.queryOptions().isEmpty());

        AiScenarioPack p1 = pack(1L, "p1");
        AiScenarioPack p2 = pack(2L, "p2");
        when(baseMapper.selectList(any())).thenReturn(List.of(p1, p2));
        AiScenarioPackTool t1 = new AiScenarioPackTool();
        t1.setPackId(1L);
        AiScenarioPackTool t2 = new AiScenarioPackTool();
        t2.setPackId(1L);
        when(packToolMapper.selectList(any())).thenReturn(List.of(t1, t2));
        AiScenarioPackMcpTool m1 = new AiScenarioPackMcpTool();
        m1.setPackId(2L);
        when(packMcpToolMapper.selectList(any())).thenReturn(List.of(m1));
        AiScenarioPackSkill s1 = new AiScenarioPackSkill();
        s1.setPackId(2L);
        when(packSkillMapper.selectList(any())).thenReturn(List.of(s1));

        List<AiScenarioPackOptionVo> options = service.queryOptions();

        assertEquals(2, options.size());
        assertEquals(2, options.get(0).getToolCount().intValue());
        assertEquals(1, options.get(1).getMcpToolCount().intValue());
        assertEquals(1, options.get(1).getSkillCount().intValue());
    }

    @Test
    @DisplayName("MCP 工具选项按白名单拆分")
    void shouldQueryMcpToolOptions() {
        AiMcpClient client = new AiMcpClient();
        client.setId(1L);
        client.setServerName("srv");
        client.setEnableTools(" a , b , a ");
        when(mcpClientMapper.selectList(any())).thenReturn(List.of(client));

        List<AiScenarioPackMcpOptionVo> options = service.queryMcpToolOptions();

        assertEquals(1, options.size());
        assertEquals(List.of("a", "b"), options.get(0).getTools());
    }

    @Test
    @DisplayName("取启用包：空/缺失/停用返回 null")
    void shouldGetEnabledPackOrNull() {
        assertNull(service.getEnabledPackOrNull(null));
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertNull(service.getEnabledPackOrNull(1L));

        AiScenarioPack disabled = new AiScenarioPack();
        disabled.setStatus(AiScenarioPack.STATUS_DISABLED);
        when(baseMapper.selectById(2L)).thenReturn(disabled);
        assertNull(service.getEnabledPackOrNull(2L));

        AiScenarioPack enabled = new AiScenarioPack();
        enabled.setStatus(AiScenarioPack.STATUS_ENABLED);
        when(baseMapper.selectById(3L)).thenReturn(enabled);
        assertNotNull(service.getEnabledPackOrNull(3L));
    }

    @Test
    @DisplayName("选用校验：空通过、缺失/停用/越权拒绝")
    void shouldValidateSelectable() {
        service.validateSelectable(null);

        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.validateSelectable(1L));

        AiScenarioPack disabled = new AiScenarioPack();
        disabled.setStatus(AiScenarioPack.STATUS_DISABLED);
        when(baseMapper.selectById(2L)).thenReturn(disabled);
        assertThrows(ServiceException.class, () -> service.validateSelectable(2L));

        AiScenarioPack all = new AiScenarioPack();
        all.setStatus(AiScenarioPack.STATUS_ENABLED);
        all.setVisibleScope(AiScenarioPack.SCOPE_ALL);
        when(baseMapper.selectById(3L)).thenReturn(all);
        service.validateSelectable(3L);

        AiScenarioPack self = new AiScenarioPack();
        self.setStatus(AiScenarioPack.STATUS_ENABLED);
        self.setVisibleScope(AiScenarioPack.SCOPE_SELF);
        self.setCreateBy(100L);
        when(baseMapper.selectById(4L)).thenReturn(self);
        assertThrows(ServiceException.class, () -> service.validateSelectable(4L));
    }

    @Test
    @DisplayName("包内绑定查询：空主键返回空列表")
    void shouldQueryPackBindings() {
        assertTrue(service.queryPackTools(null).isEmpty());
        assertTrue(service.queryPackMcpTools(null).isEmpty());
        assertTrue(service.queryPackSkillIds(null).isEmpty());

        AiScenarioPackTool tool = new AiScenarioPackTool();
        when(packToolMapper.selectList(any())).thenReturn(List.of(tool));
        assertEquals(1, service.queryPackTools(1L).size());

        AiScenarioPackMcpTool mcp = new AiScenarioPackMcpTool();
        when(packMcpToolMapper.selectList(any())).thenReturn(List.of(mcp));
        assertEquals(1, service.queryPackMcpTools(1L).size());

        AiScenarioPackSkill skill = new AiScenarioPackSkill();
        skill.setSkillId(9L);
        when(packSkillMapper.selectList(any())).thenReturn(List.of(skill));
        assertEquals(List.of(9L), service.queryPackSkillIds(1L));
    }

    private static AiScenarioPack pack(Long id, String code) {
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(id);
        pack.setPackCode(code);
        return pack;
    }

    private static AiScenarioPackBo packBo(String code) {
        AiScenarioPackBo bo = new AiScenarioPackBo();
        bo.setPackCode(code);
        bo.setPackName("名称");
        bo.setVisibleScope(AiScenarioPack.SCOPE_ALL);
        return bo;
    }

}