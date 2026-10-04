package org.dromara.ai.service.impl;

import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.AiScenarioPackSkill;
import org.dromara.ai.domain.AiScenarioPackTool;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiSessionResource;
import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiSessionResourceBatchBo;
import org.dromara.ai.domain.bo.AiSessionResourceItemBo;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.vo.AiSessionResourceVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.mapper.AiScenarioPackMapper;
import org.dromara.ai.mapper.AiSessionResourceMapper;
import org.dromara.ai.mapper.AiSkillMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.common.core.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiSessionResourceServiceImpl} 单元测试：资源装配、展示渲染与增删校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("会话级资源集 Service")
class AiSessionResourceServiceImplTest {

    @Mock
    private AiSessionResourceMapper resourceMapper;

    @Mock
    private AiScenarioPackMapper packMapper;

    @Mock
    private AiToolMapper toolMapper;

    @Mock
    private AiSkillMapper skillMapper;

    @Mock
    private AiMcpClientMapper mcpClientMapper;

    @Mock
    private IAiScenarioPackService scenarioPackService;

    @Mock
    private IAiSkillService skillService;

    @InjectMocks
    private AiSessionResourceServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单元测试不启动 MyBatis，需手动初始化实体元数据以支持 LambdaQueryWrapper
        for (Class<?> clazz : new Class<?>[]{AiSessionResource.class, AiScenarioPack.class, AiTool.class,
            AiSkill.class, AiMcpClient.class, AiScenarioPackTool.class, AiScenarioPackMcpTool.class,
            AiScenarioPackSkill.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
        }
    }

    @Test
    @DisplayName("空会话或空主键返回空资源集")
    void shouldReturnEmptyForBlankSession() {
        assertNull(service.resolveForSession(null).sessionId());
        AiSession session = new AiSession();
        assertEquals(AiSessionResourceSet.empty().signature(), service.resolveForSession(session).signature());
    }

    @Test
    @DisplayName("会话无资源行时返回仅带会话主键的空集")
    void shouldReturnEmptySetWhenNoRows() {
        when(resourceMapper.selectList(any())).thenReturn(List.of());
        AiSession session = new AiSession();
        session.setId(9L);

        AiSessionResourceSet set = service.resolveForSession(session);

        assertEquals(9L, set.sessionId().longValue());
        assertEquals(AiSessionResourceSet.signature(List.of()), set.signature());
        assertEquals("", set.packSignature());
    }

    @Test
    @DisplayName("展开直接选中与场景包资源")
    void shouldResolveDirectAndPackResources() {
        AiTool tool = new AiTool();
        tool.setId(1L);
        tool.setToolCode("read_a");
        tool.setToolType(AiTool.TYPE_READ_ONLY);
        when(toolMapper.selectByIds(any())).thenReturn(List.of(tool));

        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(10L);
        pack.setPackCode("p1");
        when(scenarioPackService.getEnabledPackOrNull(10L)).thenReturn(pack);
        AiScenarioPackTool packTool = new AiScenarioPackTool();
        packTool.setToolCode("t1");
        packTool.setDecision(AiScenarioPackTool.DECISION_ALLOW);
        when(scenarioPackService.queryPackTools(10L)).thenReturn(List.of(packTool));
        AiScenarioPackMcpTool packMcp = new AiScenarioPackMcpTool();
        packMcp.setMcpClientId(11L);
        packMcp.setToolName("mcp_x");
        when(scenarioPackService.queryPackMcpTools(10L)).thenReturn(List.of(packMcp));
        when(scenarioPackService.queryPackSkillIds(10L)).thenReturn(List.of(9L));

        when(resourceMapper.selectList(any())).thenReturn(List.of(
            row(AiSessionResource.TYPE_TOOL, 1L, null),
            row(AiSessionResource.TYPE_TOOL, 2L, "write_b"),
            row(AiSessionResource.TYPE_TOOL, 3L, "  "),
            row(AiSessionResource.TYPE_SKILL, 5L, null),
            row(AiSessionResource.TYPE_SKILL, null, null),
            row(AiSessionResource.TYPE_MCP_TOOL, 7L, "m1"),
            row(AiSessionResource.TYPE_MCP_TOOL, 8L, " "),
            row(AiSessionResource.TYPE_SCENARIO, 10L, null)
        ));

        AiSession session = new AiSession();
        session.setId(9L);
        AiSessionResourceSet set = service.resolveForSession(session);

        assertEquals(AiScenarioPackTool.DECISION_ALLOW, set.directToolDecisions().get("read_a"));
        assertEquals(AiScenarioPackTool.DECISION_ASK, set.directToolDecisions().get("write_b"));
        assertEquals(List.of(5L), set.directSkillIds());
        assertEquals(1, set.directMcpTools().size());
        assertEquals(7L, set.directMcpTools().get(0).getMcpClientId().longValue());
        assertEquals(AiScenarioPackTool.DECISION_ALLOW, set.packToolDecisions().get("t1"));
        assertEquals(List.of(9L), set.packSkillIds());
        assertEquals(1, set.packMcpTools().size());
        assertEquals(9L, set.sessionId().longValue());
        assertTrue(set.packSignature().length() > 0);
    }

    @Test
    @DisplayName("旧会话无场景行时按单包兼容读取")
    void shouldFallbackToLegacyScenarioPack() {
        when(resourceMapper.selectList(any())).thenReturn(List.of());
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(20L);
        when(scenarioPackService.getEnabledPackOrNull(20L)).thenReturn(pack);

        AiSession session = new AiSession();
        session.setId(9L);
        session.setScenarioPackId(20L);
        AiSessionResourceSet set = service.resolveForSession(session);

        assertEquals(9L, set.sessionId().longValue());
        assertNotNull(set.packSignature());
    }

    @Test
    @DisplayName("单包装配：空包返回空集，正常包展开绑定")
    void shouldBuildSetForSinglePack() {
        assertEquals(AiSessionResourceSet.empty().signature(), service.ofSinglePack(null).signature());
        AiScenarioPack blank = new AiScenarioPack();
        assertEquals(AiSessionResourceSet.empty().signature(), service.ofSinglePack(blank).signature());

        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(5L);
        pack.setPackCode("px");
        AiScenarioPackTool tool = new AiScenarioPackTool();
        tool.setToolCode("t");
        tool.setDecision(AiScenarioPackTool.DECISION_DENY);
        when(scenarioPackService.queryPackTools(5L)).thenReturn(List.of(tool));
        when(scenarioPackService.queryPackMcpTools(5L)).thenReturn(List.of());
        when(scenarioPackService.queryPackSkillIds(5L)).thenReturn(List.of(3L));

        AiSessionResourceSet set = service.ofSinglePack(pack);

        assertEquals(AiScenarioPackTool.DECISION_DENY, set.packToolDecisions().get("t"));
        assertEquals(List.of(3L), set.packSkillIds());
        assertNull(set.sessionId());
    }

    @Test
    @DisplayName("资源行渲染：无行的会话返回空列表")
    void shouldReturnEmptyVoWhenNoRows() {
        when(resourceMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.queryResources(1L).isEmpty());
    }

    @Test
    @DisplayName("资源行渲染展示名与副标签")
    void shouldRenderResourceVoLabels() {
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(10L);
        pack.setPackName("P");
        when(packMapper.selectByIds(any())).thenReturn(List.of(pack));
        AiTool tool = new AiTool();
        tool.setId(1L);
        tool.setToolName("T");
        tool.setToolType(AiTool.TYPE_READ_ONLY);
        when(toolMapper.selectByIds(any())).thenReturn(List.of(tool));
        AiSkill skill = new AiSkill();
        skill.setId(5L);
        skill.setName("S");
        when(skillMapper.selectByIds(any())).thenReturn(List.of(skill));
        AiMcpClient client = new AiMcpClient();
        client.setId(7L);
        client.setServerName("srv");
        when(mcpClientMapper.selectByIds(any())).thenReturn(List.of(client));

        when(resourceMapper.selectList(any())).thenReturn(List.of(
            row(AiSessionResource.TYPE_SCENARIO, 10L, null),
            row(AiSessionResource.TYPE_TOOL, 1L, null),
            row(AiSessionResource.TYPE_SKILL, 5L, null),
            row(AiSessionResource.TYPE_MCP_TOOL, 7L, "m"),
            row("unknown", 9L, "x")
        ));

        List<AiSessionResourceVo> vos = service.queryResources(1L);

        assertEquals(5, vos.size());
        assertEquals("P", vos.get(0).getDisplayName());
        assertEquals("场景包", vos.get(0).getSubLabel());
        assertEquals("T", vos.get(1).getDisplayName());
        assertEquals(AiTool.TYPE_READ_ONLY, vos.get(1).getToolType());
        assertEquals("S", vos.get(2).getDisplayName());
        assertEquals("MCP · srv", vos.get(3).getSubLabel());
        assertEquals("x", vos.get(4).getDisplayName());
    }

    @Test
    @DisplayName("资源行渲染：查不到本体时回退稳定键")
    void shouldFallbackToResourceKeyWhenEntityMissing() {
        when(resourceMapper.selectList(any())).thenReturn(List.of(
            row(AiSessionResource.TYPE_SCENARIO, 10L, "rc"),
            row(AiSessionResource.TYPE_TOOL, 1L, "rt"),
            row(AiSessionResource.TYPE_SKILL, 5L, "rs"),
            row(AiSessionResource.TYPE_MCP_TOOL, 7L, "rm")
        ));

        List<AiSessionResourceVo> vos = service.queryResources(1L);

        assertEquals("rc", vos.get(0).getDisplayName());
        assertEquals("rt", vos.get(1).getDisplayName());
        assertEquals("rs", vos.get(2).getDisplayName());
        assertEquals("MCP 工具", vos.get(3).getSubLabel());
    }

    @Test
    @DisplayName("批量新增：空批次直接成功")
    void shouldSkipEmptyAddBatch() {
        assertTrue(service.addResources(1L, null));
        AiSessionResourceBatchBo bo = new AiSessionResourceBatchBo();
        assertTrue(service.addResources(1L, bo));
        bo.setItems(List.of());
        assertTrue(service.addResources(1L, bo));
        verify(resourceMapper, never()).insert(any(AiSessionResource.class));
    }

    @Test
    @DisplayName("批量新增：四类合法资源各插入一行")
    void shouldInsertValidResources() {
        AiTool tool = new AiTool();
        tool.setId(1L);
        tool.setToolCode("tc");
        tool.setStatus(AiTool.STATUS_ENABLED);
        when(toolMapper.selectById(1L)).thenReturn(tool);

        AiSkill skill = new AiSkill();
        skill.setId(3L);
        skill.setName("sk");
        when(skillService.getEnabledSkill(3L)).thenReturn(skill);

        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(4L);
        pack.setPackCode("pc");
        when(scenarioPackService.getEnabledPackOrNull(4L)).thenReturn(pack);

        AiMcpClient client = new AiMcpClient();
        client.setId(6L);
        client.setStatus(AiMcpClient.STATUS_ENABLED);
        client.setEnableTools("toolA, b");
        when(mcpClientMapper.selectById(6L)).thenReturn(client);
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        when(resourceMapper.insert(any(AiSessionResource.class))).thenReturn(1);

        AiSessionResourceBatchBo bo = batch(
            item(AiSessionResource.TYPE_TOOL, 1L, null),
            item(AiSessionResource.TYPE_SKILL, 3L, null),
            item(AiSessionResource.TYPE_SCENARIO, 4L, null),
            item(AiSessionResource.TYPE_MCP_TOOL, 6L, "toolA"));

        assertTrue(service.addResources(1L, bo));
        verify(resourceMapper, times(4)).insert(any(AiSessionResource.class));
    }

    @Test
    @DisplayName("批量新增：已存在活跃行时幂等跳过")
    void shouldSkipExistingRows() {
        AiTool tool = new AiTool();
        tool.setId(1L);
        tool.setToolCode("tc");
        tool.setStatus(AiTool.STATUS_ENABLED);
        when(toolMapper.selectById(1L)).thenReturn(tool);
        when(resourceMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.addResources(1L, batch(item(AiSessionResource.TYPE_TOOL, 1L, null))));
        verify(resourceMapper, never()).insert(any(AiSessionResource.class));
    }

    @Test
    @DisplayName("批量新增：资源主键为空时跳过")
    void shouldSkipItemWithoutResourceId() {
        assertTrue(service.addResources(1L, batch(item(AiSessionResource.TYPE_TOOL, null, null))));
        verify(resourceMapper, never()).insert(any(AiSessionResource.class));
    }

    @Test
    @DisplayName("批量新增：非法类型或工具不存在时拒绝")
    void shouldRejectInvalidAddItems() {
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item("unknown", 1L, null))));

        when(toolMapper.selectById(2L)).thenReturn(null);
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item(AiSessionResource.TYPE_TOOL, 2L, null))));

        AiTool disabled = new AiTool();
        disabled.setId(3L);
        disabled.setStatus("1");
        when(toolMapper.selectById(3L)).thenReturn(disabled);
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item(AiSessionResource.TYPE_TOOL, 3L, null))));
    }

    @Test
    @DisplayName("批量新增：技能名为空回退主键、场景包缺失回退主键")
    void shouldNormalizeMissingNameValues() {
        AiSkill skill = new AiSkill();
        skill.setId(3L);
        when(skillService.getEnabledSkill(3L)).thenReturn(skill);
        when(scenarioPackService.getEnabledPackOrNull(4L)).thenReturn(null);
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        when(resourceMapper.insert(any(AiSessionResource.class))).thenReturn(1);

        AiSessionResourceBatchBo bo = batch(
            item(AiSessionResource.TYPE_SKILL, 3L, null),
            item(AiSessionResource.TYPE_SCENARIO, 4L, null));

        assertTrue(service.addResources(1L, bo));
        verify(resourceMapper, times(2)).insert(any(AiSessionResource.class));
    }

    @Test
    @DisplayName("批量新增：MCP 工具缺失/停用/白名单外时拒绝")
    void shouldRejectInvalidMcpSelections() {
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item(AiSessionResource.TYPE_MCP_TOOL, 6L, " "))));

        when(mcpClientMapper.selectById(6L)).thenReturn(null);
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item(AiSessionResource.TYPE_MCP_TOOL, 6L, "x"))));

        AiMcpClient disabled = new AiMcpClient();
        disabled.setId(6L);
        disabled.setStatus("1");
        when(mcpClientMapper.selectById(6L)).thenReturn(disabled);
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item(AiSessionResource.TYPE_MCP_TOOL, 6L, "x"))));

        AiMcpClient enabled = new AiMcpClient();
        enabled.setId(6L);
        enabled.setStatus(AiMcpClient.STATUS_ENABLED);
        enabled.setServerName("srv");
        enabled.setEnableTools("a,b");
        when(mcpClientMapper.selectById(6L)).thenReturn(enabled);
        assertThrows(ServiceException.class,
            () -> service.addResources(1L, batch(item(AiSessionResource.TYPE_MCP_TOOL, 6L, "c"))));
    }

    @Test
    @DisplayName("批量移除：空批次直接成功")
    void shouldSkipEmptyRemoveBatch() {
        assertTrue(service.removeResources(1L, null));
        verify(resourceMapper, never()).delete(any());
    }

    @Test
    @DisplayName("批量移除：跳过非法项并按类型删除")
    void shouldRemoveByType() {
        when(resourceMapper.delete(any())).thenReturn(1);

        AiSessionResourceBatchBo bo = batch(
            null,
            item(null, 1L, null),
            item(AiSessionResource.TYPE_TOOL, null, null),
            item("unknown", 2L, null),
            item(AiSessionResource.TYPE_TOOL, 3L, null),
            item(AiSessionResource.TYPE_MCP_TOOL, 4L, "  m4  "));

        assertTrue(service.removeResources(1L, bo));
        verify(resourceMapper, times(2)).delete(any());
    }

    private static AiSessionResource row(String type, Long resourceId, String key) {
        AiSessionResource row = new AiSessionResource();
        row.setId(resourceId);
        row.setResourceType(type);
        row.setResourceId(resourceId);
        row.setResourceKey(key);
        return row;
    }

    private static AiSessionResourceItemBo item(String type, Long resourceId, String key) {
        AiSessionResourceItemBo item = new AiSessionResourceItemBo();
        item.setResourceType(type);
        item.setResourceId(resourceId);
        item.setResourceKey(key);
        return item;
    }

    private static AiSessionResourceBatchBo batch(AiSessionResourceItemBo... items) {
        AiSessionResourceBatchBo bo = new AiSessionResourceBatchBo();
        bo.setItems(new java.util.ArrayList<>(java.util.Arrays.asList(items)));
        return bo;
    }

}