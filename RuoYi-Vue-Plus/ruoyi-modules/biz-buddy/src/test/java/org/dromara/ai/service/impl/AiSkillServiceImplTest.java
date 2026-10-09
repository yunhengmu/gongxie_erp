package org.dromara.ai.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.ai.domain.AiAgentSkill;
import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.AiSkillResource;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiSkillBo;
import org.dromara.ai.domain.bo.AiSkillResourceBo;
import org.dromara.ai.domain.vo.AiSkillOptionVo;
import org.dromara.ai.domain.vo.AiSkillResourceVo;
import org.dromara.ai.domain.vo.AiSkillVo;
import org.dromara.ai.mapper.AiAgentSkillMapper;
import org.dromara.ai.mapper.AiSkillMapper;
import org.dromara.ai.mapper.AiSkillResourceMapper;
import org.dromara.ai.mapper.AiToolCallLogMapper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiSkillServiceImpl} 单元测试：列表填充、生命周期、参考资料与技能可见性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("技能 Service")
class AiSkillServiceImplTest {

    @Mock
    private AiSkillMapper baseMapper;
    @Mock
    private AiSkillResourceMapper resourceMapper;
    @Mock
    private AiAgentSkillMapper agentSkillMapper;
    @Mock
    private AiToolCallLogMapper toolCallLogMapper;
    @Mock
    private UserService userService;
    @Mock
    private DeptService deptService;

    @InjectMocks
    private AiSkillServiceImpl service;

    @BeforeAll
    static void setUp() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.getBeanFactory().registerSingleton("jsonMapper", mock(JsonMapper.class));
        context.refresh();
        new SpringUtil().setApplicationContext(context);
        for (Class<?> clazz : new Class<?>[]{AiSkill.class, AiSkillResource.class, AiAgentSkill.class,
            AiToolCallLog.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
        }
    }

    @Test
    @DisplayName("分页查询填充正文、部门名、昵称与状态")
    void shouldQueryPageList() {
        AiSkillVo vo = new AiSkillVo();
        vo.setId(1L);
        vo.setName("demo");
        vo.setStatus(AiSkill.STATUS_ENABLED);
        vo.setSkillContent("---\nname: demo\n---\nbody");
        vo.setOwnerDeptId(2L);
        vo.setCreateBy(3L);
        vo.setCreatedAt(LocalDateTime.now());
        Page<AiSkillVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        doReturn(page).when(baseMapper).selectVoPage(any(), any());
        when(deptService.selectDeptNamesByIds(any())).thenReturn(Map.of(2L, "研发部"));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(3L, "张三"));
        when(resourceMapper.selectList(any())).thenReturn(List.of());
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        PageResult<AiSkillVo> result = service.queryPageList(new AiSkillBo(), new PageQuery(10, 1));

        AiSkillVo filled = result.getRows().iterator().next();
        assertEquals("body", filled.getSkillBody());
        assertEquals("研发部", filled.getOwnerDeptName());
        assertEquals("张三", filled.getCreateByName());
        assertEquals(0, filled.getResourceCount().intValue());
        assertEquals(0, filled.getUseCount().intValue());
        assertEquals("active", filled.getLifecycleState());
    }

    @Test
    @DisplayName("生命周期判定：活跃/陈旧/待归档/停用")
    void shouldResolveLifecycleState() {
        AiSkillVo active = skillVo(1L, "a", AiSkill.STATUS_ENABLED, LocalDateTime.now().minusDays(1));
        AiSkillVo stale = skillVo(2L, "b", AiSkill.STATUS_ENABLED, LocalDateTime.now().minusDays(40));
        AiSkillVo archived = skillVo(3L, "c", AiSkill.STATUS_ENABLED, LocalDateTime.now().minusDays(100));
        AiSkillVo disabled = skillVo(4L, "d", "1", LocalDateTime.now());
        Page<AiSkillVo> page = new Page<>(1, 10);
        page.setTotal(4);
        page.setRecords(List.of(active, stale, archived, disabled));
        doReturn(page).when(baseMapper).selectVoPage(any(), any());
        when(resourceMapper.selectList(any())).thenReturn(List.of());
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        service.queryPageList(new AiSkillBo(), new PageQuery(10, 1));

        assertEquals("active", active.getLifecycleState());
        assertEquals("stale", stale.getLifecycleState());
        assertEquals("archived", archived.getLifecycleState());
        assertEquals("disabled", disabled.getLifecycleState());
    }

    @Test
    @DisplayName("使用计数按工具调用明细聚合")
    void shouldAggregateUsageStats() {
        AiSkillVo vo = skillVo(1L, "demo", AiSkill.STATUS_ENABLED, LocalDateTime.now());
        Page<AiSkillVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        doReturn(page).when(baseMapper).selectVoPage(any(), any());
        when(resourceMapper.selectList(any())).thenReturn(List.of());

        LocalDateTime t1 = LocalDateTime.now().minusHours(2);
        LocalDateTime t2 = LocalDateTime.now().minusHours(1);
        AiToolCallLog blank = new AiToolCallLog();
        AiToolCallLog first = new AiToolCallLog();
        first.setToolInput("{\"skillId\":\"demo\"}");
        first.setCreateTime(t1);
        AiToolCallLog second = new AiToolCallLog();
        second.setToolInput("{}");
        second.setCreateTime(t2);
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(blank, first, second));

        try (MockedStatic<JsonUtils> ms = mockStatic(JsonUtils.class)) {
            ms.when(() -> JsonUtils.parseObject(anyString(), eq(Map.class))).thenReturn(Map.of("skillId", "demo"));
            service.queryPageList(new AiSkillBo(), new PageQuery(10, 1));
        }

        assertEquals(2, vo.getUseCount().intValue());
        assertEquals(t2, vo.getLastUsedAt());
    }

    @Test
    @DisplayName("详情查询：命中带参考资料、缺失抛异常")
    void shouldQueryById() {
        AiSkillVo vo = new AiSkillVo();
        vo.setId(1L);
        vo.setSkillContent("plain");
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());
        when(resourceMapper.selectList(any())).thenReturn(List.of());
        AiSkillResourceVo resourceVo = new AiSkillResourceVo();
        when(resourceMapper.selectVoList(any())).thenReturn(List.of(resourceVo));

        assertEquals(1, service.queryById(1L).getResources().size());

        when(baseMapper.selectVoById(2L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.queryById(2L));
    }

    @Test
    @DisplayName("新增：拼接 SKILL.md 并保存参考资料")
    void shouldInsertSkill() {
        AiSkillBo bo = new AiSkillBo();
        bo.setName("demo_skill");
        bo.setDescription("说明");
        bo.setSkillBody("正文");
        AiSkillResourceBo resource = new AiSkillResourceBo();
        resource.setResourcePath("references/a.md");
        resource.setResourceContent("内容");
        bo.setResources(List.of(resource));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiSkillBo.class), eq(AiSkill.class))).thenReturn(new AiSkill());
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiSkill.class))).thenReturn(1);
            when(resourceMapper.insert(any(AiSkillResource.class))).thenReturn(1);
            assertTrue(service.insertByBo(bo));
        }
        verify(resourceMapper, times(1)).insert(any(AiSkillResource.class));
    }

    @Test
    @DisplayName("新增：标识非法/重复、参考资料路径非法时拒绝")
    void shouldRejectInvalidInsert() {
        AiSkillBo bad = new AiSkillBo();
        bad.setName("1bad");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiSkillBo.class), eq(AiSkill.class))).thenReturn(new AiSkill());
            assertThrows(ServiceException.class, () -> service.insertByBo(bad));
        }

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiSkillBo.class), eq(AiSkill.class))).thenReturn(new AiSkill());
            when(baseMapper.selectCount(any())).thenReturn(1L);
            AiSkillBo dup = new AiSkillBo();
            dup.setName("demo_skill");
            assertThrows(ServiceException.class, () -> service.insertByBo(dup));
        }

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiSkillBo.class), eq(AiSkill.class))).thenReturn(new AiSkill());
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiSkill.class))).thenReturn(1);
            AiSkillBo bo = new AiSkillBo();
            bo.setName("demo_skill");
            AiSkillResourceBo resource = new AiSkillResourceBo();
            resource.setResourcePath("/abs.md");
            bo.setResources(List.of(resource));
            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("修改：不存在/标识变更时拒绝，一致时保存")
    void shouldUpdateSkill() {
        AiSkillBo bo = new AiSkillBo();
        bo.setId(1L);
        bo.setName("demo");
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));

        AiSkill exists = new AiSkill();
        exists.setName("demo");
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiSkillBo changed = new AiSkillBo();
        changed.setId(1L);
        changed.setName("other");
        assertThrows(ServiceException.class, () -> service.updateByBo(changed));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiSkillBo.class), eq(AiSkill.class))).thenReturn(new AiSkill());
            when(baseMapper.updateById(any(AiSkill.class))).thenReturn(1);
            assertTrue(service.updateByBo(bo));
        }
        verify(resourceMapper, times(1)).delete(any());
    }

    @Test
    @DisplayName("删除：空参/不存在/被绑定拒绝，正常删除")
    void shouldDeleteSkill() {
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of()));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiSkill()));
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L, 2L)));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiSkill(), new AiSkill()));
        when(agentSkillMapper.selectCount(any())).thenReturn(1L);
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L, 2L)));

        when(agentSkillMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteWithValidByIds(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("可见选项按用户部门解析")
    void shouldQueryVisibleOptions() {
        UserDTO user = new UserDTO();
        user.setDeptId(9L);
        when(userService.selectById(5L)).thenReturn(user);
        AiSkill skill = new AiSkill();
        skill.setId(1L);
        skill.setName("demo");
        when(baseMapper.selectList(any())).thenReturn(List.of(skill));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            AiSkillOptionVo option = new AiSkillOptionVo();
            option.setId(1L);
            ms.when(() -> MapstructUtils.convert(skill, AiSkillOptionVo.class)).thenReturn(option);
            List<AiSkillOptionVo> options = service.queryVisibleOptions(5L);
            assertEquals(1, options.size());
            assertEquals(1L, options.get(0).getId().longValue());
        }
    }

    @Test
    @DisplayName("可见技能求交集")
    void shouldFilterVisibleSkillIds() {
        assertTrue(service.filterVisibleSkillIds(null, 1L).isEmpty());
        assertTrue(service.filterVisibleSkillIds(List.of(1L), null).isEmpty());

        AiSkill s1 = new AiSkill();
        s1.setId(1L);
        AiSkill s2 = new AiSkill();
        s2.setId(2L);
        when(baseMapper.selectList(any())).thenReturn(List.of(s1, s2));

        assertEquals(List.of(1L, 2L), service.filterVisibleSkillIds(List.of(1L, 2L, 3L), 5L));
    }

    @Test
    @DisplayName("可见性校验：空通过、无用户拒绝、越权拒绝")
    void shouldValidateVisible() {
        service.validateVisible(List.of(), 1L, "绑定");

        assertThrows(ServiceException.class, () -> service.validateVisible(List.of(1L), null, "绑定"));

        AiSkill s1 = new AiSkill();
        s1.setId(1L);
        s1.setName("sk1");
        when(baseMapper.selectList(any())).thenReturn(List.of(s1));
        service.validateVisible(List.of(1L), 5L, "绑定");

        AiSkill denied = new AiSkill();
        denied.setName("sk9");
        when(baseMapper.selectById(9L)).thenReturn(denied);
        assertThrows(ServiceException.class, () -> service.validateVisible(List.of(1L, 9L), 5L, "绑定"));
    }

    @Test
    @DisplayName("取启用技能：空/缺失/停用抛异常，启用返回")
    void shouldGetEnabledSkill() {
        assertThrows(ServiceException.class, () -> service.getEnabledSkill(null));
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.getEnabledSkill(1L));

        AiSkill disabled = new AiSkill();
        disabled.setStatus("1");
        when(baseMapper.selectById(2L)).thenReturn(disabled);
        assertThrows(ServiceException.class, () -> service.getEnabledSkill(2L));

        AiSkill enabled = new AiSkill();
        enabled.setStatus(AiSkill.STATUS_ENABLED);
        when(baseMapper.selectById(3L)).thenReturn(enabled);
        assertNotNull(service.getEnabledSkill(3L));
    }

    @Test
    @DisplayName("运行期/装配期过滤键：绑定并集后生成两种键形式")
    void shouldResolveFilterKeys() {
        AiAgentSkill binding = new AiAgentSkill();
        binding.setAgentId(1L);
        binding.setSkillId(1L);
        when(agentSkillMapper.selectList(any())).thenReturn(List.of(binding));
        AiSkill s1 = new AiSkill();
        s1.setId(1L);
        s1.setName("sk1");
        s1.setSource(AiSkill.SOURCE_BIZBUDDY);
        s1.setStatus(AiSkill.STATUS_ENABLED);
        AiSkill s2 = new AiSkill();
        s2.setId(2L);
        s2.setName("sk2");
        s2.setSource(AiSkill.SOURCE_BIZBUDDY);
        s2.setStatus(AiSkill.STATUS_ENABLED);
        when(baseMapper.selectList(any())).thenReturn(List.of(s1, s2));
        when(baseMapper.selectByIds(any())).thenReturn(List.of(s1, s2));

        List<String> runtime = service.resolveRuntimeSkillFilterKeys(1L, List.of(2L), 5L);
        assertTrue(runtime.contains("sk1"));
        assertTrue(runtime.contains("sk1_bizbuddy"));
        assertTrue(runtime.contains("sk2"));

        List<String> assembly = service.resolveAgentSkillFilterKeys(1L, List.of(2L));
        assertTrue(assembly.contains("sk2"));
    }

    @Test
    @DisplayName("绑定查询与映射")
    void shouldQueryBindings() {
        assertTrue(service.queryBoundSkillIds(null).isEmpty());
        AiAgentSkill binding = new AiAgentSkill();
        binding.setAgentId(1L);
        binding.setSkillId(2L);
        when(agentSkillMapper.selectList(any())).thenReturn(List.of(binding));
        assertEquals(List.of(2L), service.queryBoundSkillIds(1L));

        assertTrue(service.queryAgentSkillMap(null).isEmpty());
        Map<Long, List<Long>> map = service.queryAgentSkillMap(List.of(1L));
        assertEquals(List.of(2L), map.get(1L));
    }

    @Test
    @DisplayName("覆盖/清理智能体技能绑定")
    void shouldManageAgentBindings() {
        service.replaceAgentBindings(1L, null);
        verify(agentSkillMapper, times(1)).delete(any());
        verify(agentSkillMapper, never()).insert(any(AiAgentSkill.class));

        service.replaceAgentBindings(1L, List.of(2L, 2L));
        verify(agentSkillMapper, times(1)).insert(any(AiAgentSkill.class));

        service.deleteAgentBindings(List.of());
        service.deleteAgentBindings(List.of(1L));
        verify(agentSkillMapper, times(3)).delete(any());
    }

    private static AiSkillVo skillVo(Long id, String name, String status, LocalDateTime createdAt) {
        AiSkillVo vo = new AiSkillVo();
        vo.setId(id);
        vo.setName(name);
        vo.setStatus(status);
        vo.setCreatedAt(createdAt);
        return vo;
    }

}