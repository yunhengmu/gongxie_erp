package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentToolTrust;
import org.dromara.ai.domain.AiEntryAgent;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiAgentToolBo;
import org.dromara.ai.domain.bo.AiEntryAgentBo;
import org.dromara.ai.domain.bo.AiExpertAgentBo;
import org.dromara.ai.domain.vo.AiAgentToolTrustVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiEntryAgentVo;
import org.dromara.ai.domain.vo.AiExpertAgentVo;
import org.dromara.ai.domain.vo.AiExpertVo;
import org.dromara.ai.mapper.AiAgentKbMapper;
import org.dromara.ai.mapper.AiAgentToolMapper;
import org.dromara.ai.mapper.AiAgentToolTrustMapper;
import org.dromara.ai.mapper.AiEntryAgentMapper;
import org.dromara.ai.mapper.AiExpertAgentMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiSubagentService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiAgentServiceImpl} 单元测试：两表解析、定义校验、绑定保存与工具信任决策。
 */
@DisplayName("AI 智能体定义服务")
class AiAgentServiceImplTest {

    private final AiEntryAgentMapper entryMapper = mock(AiEntryAgentMapper.class);
    private final AiExpertAgentMapper expertMapper = mock(AiExpertAgentMapper.class);
    private final AiAgentToolMapper agentToolMapper = mock(AiAgentToolMapper.class);
    private final AiAgentToolTrustMapper agentToolTrustMapper = mock(AiAgentToolTrustMapper.class);
    private final AiAgentKbMapper agentKbMapper = mock(AiAgentKbMapper.class);
    private final AiModelConfigMapper modelConfigMapper = mock(AiModelConfigMapper.class);
    private final AiSessionMapper sessionMapper = mock(AiSessionMapper.class);
    private final IAiKnowledgeBaseService knowledgeBaseService = mock(IAiKnowledgeBaseService.class);
    private final IAiToolService toolService = mock(IAiToolService.class);
    private final IAiSkillService skillService = mock(IAiSkillService.class);
    private final IAiSubagentService subagentService = mock(IAiSubagentService.class);
    private final AgentRegistry agentRegistry = mock(AgentRegistry.class);
    private final UserService userService = mock(UserService.class);
    private final AiProperties aiProperties = new AiProperties();
    private final AiAgentServiceImpl service = new AiAgentServiceImpl(entryMapper, expertMapper, agentToolMapper,
        agentToolTrustMapper, agentKbMapper, modelConfigMapper, sessionMapper, knowledgeBaseService, toolService,
        skillService, subagentService, agentRegistry, userService, aiProperties);

    private MockedStatic<MapstructUtils> mapstruct;
    private MockedStatic<LoginHelper> loginHelper;

    @BeforeAll
    static void initContext() {
        AiServiceTestContext.initialize();
    }

    @BeforeEach
    void setUp() {
        mapstruct = mockStatic(MapstructUtils.class);
        mapstruct.when(() -> MapstructUtils.convert(any(AiEntryAgent.class), eq(AiEntryAgentVo.class)))
            .thenAnswer(inv -> AiServiceTestContext.copy(inv.getArgument(0), AiEntryAgentVo.class));
        mapstruct.when(() -> MapstructUtils.convert(any(AiExpertAgent.class), eq(AiExpertAgentVo.class)))
            .thenAnswer(inv -> AiServiceTestContext.copy(inv.getArgument(0), AiExpertAgentVo.class));
        mapstruct.when(() -> MapstructUtils.convert(any(AiEntryAgentBo.class), eq(AiEntryAgent.class)))
            .thenAnswer(inv -> AiServiceTestContext.copy(inv.getArgument(0), AiEntryAgent.class));
        mapstruct.when(() -> MapstructUtils.convert(any(AiExpertAgentBo.class), eq(AiExpertAgent.class)))
            .thenAnswer(inv -> AiServiceTestContext.copy(inv.getArgument(0), AiExpertAgent.class));

        loginHelper = mockStatic(LoginHelper.class);
        loginHelper.when(LoginHelper::getUserId).thenReturn(9L);
        loginHelper.when(LoginHelper::getDeptId).thenReturn(3L);
    }

    @AfterEach
    void tearDown() {
        loginHelper.close();
        mapstruct.close();
    }

    @Test
    @DisplayName("入口智能体未配置时返回空")
    void shouldReturnNullWhenEntryMissing() {
        when(entryMapper.selectList(any())).thenReturn(List.of());
        assertNull(service.queryEntryAgent());
    }

    @Test
    @DisplayName("查询入口智能体回填模型名称与角色")
    void shouldQueryEntryAgent() {
        AiEntryAgent entry = entry(1L, "0");
        entry.setModelId(5L);
        when(entryMapper.selectList(any())).thenReturn(List.of(entry));
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(chatModel(5L, "gpt-4o")));

        AiEntryAgentVo vo = service.queryEntryAgent();

        assertEquals(AiEntryAgent.ROLE_ENTRY, vo.getRole());
        assertEquals("gpt-4o", vo.getModelName());
    }

    @Test
    @DisplayName("入口智能体未配置时拒绝修改")
    void shouldRejectUpdateEntryWhenMissing() {
        when(entryMapper.selectList(any())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.updateEntryAgent(new AiEntryAgentBo()));
    }

    @Test
    @DisplayName("修改入口智能体成功并清理装配缓存")
    void shouldUpdateEntryAgent() {
        AiEntryAgent entry = entry(1L, "0");
        when(entryMapper.selectList(any())).thenReturn(List.of(entry));
        when(modelConfigMapper.selectById(5L)).thenReturn(chatModel(5L, "gpt"));
        when(entryMapper.updateById(any(AiEntryAgent.class))).thenReturn(1);
        AiEntryAgentBo bo = new AiEntryAgentBo();
        bo.setAgentCode("entry_x");
        bo.setModelId(5L);

        assertTrue(service.updateEntryAgent(bo));
        verify(agentRegistry).evict(1L);
    }

    @Test
    @DisplayName("修改入口智能体时编码非法抛出异常")
    void shouldRejectUpdateEntryWithInvalidCode() {
        when(entryMapper.selectList(any())).thenReturn(List.of(entry(1L, "0")));
        AiEntryAgentBo bo = new AiEntryAgentBo();
        bo.setAgentCode("1bad");
        bo.setModelId(5L);

        assertThrows(ServiceException.class, () -> service.updateEntryAgent(bo));
    }

    @Test
    @DisplayName("绑定非对话模型时拒绝修改")
    void shouldRejectNonChatModel() {
        when(entryMapper.selectList(any())).thenReturn(List.of(entry(1L, "0")));
        AiModelConfig embedding = chatModel(5L, "emb");
        embedding.setModelType("embedding");
        when(modelConfigMapper.selectById(5L)).thenReturn(embedding);
        AiEntryAgentBo bo = new AiEntryAgentBo();
        bo.setAgentCode("entry_x");
        bo.setModelId(5L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateEntryAgent(bo));
        assertTrue(ex.getMessage().contains("不是对话模型"));
    }

    @Test
    @DisplayName("按主键查询入口智能体角色为入口")
    void shouldQueryEntryById() {
        when(entryMapper.selectById(1L)).thenReturn(entry(1L, "0"));

        AiAgentVo vo = service.queryById(1L);

        assertEquals(AiEntryAgent.ROLE_ENTRY, vo.getRole());
    }

    @Test
    @DisplayName("按主键查询专家智能体角色为专家")
    void shouldQueryExpertById() {
        when(entryMapper.selectById(1L)).thenReturn(null);
        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(1L);
        when(expertMapper.selectById(1L)).thenReturn(expert);

        AiAgentVo vo = service.queryById(1L);

        assertEquals(AiAgent.ROLE_EXPERT, vo.getRole());
    }

    @Test
    @DisplayName("主键为空时查询返回空")
    void shouldReturnNullForNullId() {
        assertNull(service.queryById(null));
    }

    @Test
    @DisplayName("分页查询专家列表")
    void shouldQueryPageList() {
        AiExpertAgentVo vo = new AiExpertAgentVo();
        vo.setId(1L);
        Page<AiExpertAgentVo> page = new Page<>(1, 10);
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(expertMapper.selectVoPage(any(), any())).thenReturn(page);
        when(agentToolMapper.selectList(any())).thenReturn(List.of());
        when(agentKbMapper.selectList(any())).thenReturn(List.of());
        when(skillService.queryAgentSkillMap(any())).thenReturn(Map.of());
        when(subagentService.queryAgentSubagentMap(any())).thenReturn(Map.of());

        PageResult<AiExpertAgentVo> result = service.queryPageList(new AiExpertAgentBo(), new PageQuery(10, 1));

        assertEquals(1L, result.getTotal());
    }

    @Test
    @DisplayName("新增专家成功并保存绑定")
    void shouldInsertExpert() {
        when(modelConfigMapper.selectById(5L)).thenReturn(chatModel(5L, "gpt"));
        when(expertMapper.insert(any(AiExpertAgent.class))).thenReturn(1);
        AiExpertAgentBo bo = expertBo("expert_a", 5L);

        assertTrue(service.insertByBo(bo));

        verify(skillService).replaceAgentBindings(any(), anyCollection());
        verify(subagentService).replaceAgentBindings(any(), anyCollection());
    }

    @Test
    @DisplayName("新增专家编码非法时抛出异常")
    void shouldRejectInsertExpertWithInvalidCode() {
        AiExpertAgentBo bo = expertBo("A B", 5L);
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("绑定不存在的业务工具时抛出异常")
    void shouldRejectInsertWithMissingTool() {
        when(modelConfigMapper.selectById(5L)).thenReturn(chatModel(5L, "gpt"));
        AiExpertAgentBo bo = expertBo("expert_b", 5L);
        AiAgentToolBo tool = new AiAgentToolBo();
        tool.setToolCode("t1");
        bo.setToolBindings(List.of(tool));
        when(toolService.exists("t1")).thenReturn(false);

        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("修改不存在的专家抛出异常")
    void shouldRejectUpdateExpertWhenMissing() {
        AiExpertAgentBo bo = expertBo("expert_c", 5L);
        bo.setId(99L);
        when(expertMapper.selectById(99L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    @Test
    @DisplayName("删除专家：存在会话时拒绝")
    void shouldRejectDeleteWhenSessionExists() {
        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(1L);
        when(expertMapper.selectByIds(any())).thenReturn(List.of(expert));
        when(sessionMapper.selectCount(any())).thenReturn(1L);

        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));
    }

    @Test
    @DisplayName("删除专家成功时清理绑定与缓存")
    void shouldDeleteExpert() {
        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(1L);
        when(expertMapper.selectByIds(any())).thenReturn(List.of(expert));
        when(sessionMapper.selectCount(any())).thenReturn(0L);
        when(expertMapper.deleteByIds(any())).thenReturn(1);

        assertTrue(service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));

        verify(skillService).deleteAgentBindings(any());
        verify(agentToolTrustMapper).delete(any());
        verify(agentRegistry).evict(1L);
    }

    @Test
    @DisplayName("查询智能体选项：入口加可见专家")
    void shouldQueryOptions() {
        when(entryMapper.selectList(any())).thenReturn(List.of(entry(1L, "0")));
        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(2L);
        when(expertMapper.selectList(any())).thenReturn(List.of(expert));

        assertEquals(2, service.queryOptions().size());
    }

    @Test
    @DisplayName("查询自动候选与全部可见专家")
    void shouldQueryExperts() {
        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(2L);
        expert.setAgentCode("fin");
        expert.setDescription("金融");
        when(expertMapper.selectList(any())).thenReturn(List.of(expert));

        List<AiExpertVo> experts = service.queryExperts();
        List<AiExpertVo> visible = service.queryVisibleExperts();

        assertEquals(1, experts.size());
        assertEquals(1, visible.size());
        assertEquals("金融", experts.get(0).getCapabilityDesc());
    }

    @Test
    @DisplayName("获取启用智能体：不存在或停用时抛出异常")
    void shouldValidateEnabledAgent() {
        assertThrows(ServiceException.class, () -> service.getEnabledAgent(null));

        AiEntryAgent disabled = entry(1L, "1");
        when(entryMapper.selectById(1L)).thenReturn(disabled);
        assertThrows(ServiceException.class, () -> service.getEnabledAgent(1L));

        AiEntryAgent normal = entry(2L, "0");
        when(entryMapper.selectById(2L)).thenReturn(normal);
        assertNotNull(service.getEnabledAgent(2L));
    }

    @Test
    @DisplayName("批量解析智能体实体与视图对象")
    void shouldQueryEntities() {
        AiEntryAgent entry = entry(1L, "0");
        AiExpertAgent expert = new AiExpertAgent();
        expert.setId(2L);
        when(entryMapper.selectByIds(any())).thenReturn(List.of(entry));
        when(expertMapper.selectByIds(any())).thenReturn(List.of(expert));

        assertEquals(2, service.queryEntities(Arrays.asList(1L, 2L)).size());
        assertEquals(2, service.queryEntityVos(Arrays.asList(1L, 2L)).size());
        assertTrue(service.queryEntities(List.of()).isEmpty());
    }

    @Test
    @DisplayName("记住工具：参数非法返回 false")
    void shouldRejectRememberWithInvalidArgs() {
        assertFalse(service.rememberToolForAgent(null, 9L, "t"));
        assertFalse(service.rememberToolForAgent(1L, null, "t"));
        assertFalse(service.rememberToolForAgent(1L, 9L, " "));
    }

    @Test
    @DisplayName("记住工具：首次写入信任决策")
    void shouldRememberNewTool() {
        when(agentToolTrustMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.rememberToolForAgent(1L, 9L, "t1"));

        verify(agentToolTrustMapper).insert(any(AiAgentToolTrust.class));
    }

    @Test
    @DisplayName("记住工具：已有非允许决策时改写为允许")
    void shouldUpgradeExistingTrust() {
        AiAgentToolTrust exists = new AiAgentToolTrust();
        exists.setDecision(AiAgentToolTrust.DECISION_ASK);
        when(agentToolTrustMapper.selectOne(any())).thenReturn(exists);

        assertTrue(service.rememberToolForAgent(1L, 9L, "t1"));

        verify(agentToolTrustMapper).update(any(AiAgentToolTrust.class), any());
    }

    @Test
    @DisplayName("记住工具：已是允许决策时不重复写入")
    void shouldSkipExistingAllowTrust() {
        AiAgentToolTrust exists = new AiAgentToolTrust();
        exists.setDecision(AiAgentToolTrust.DECISION_ALLOW);
        when(agentToolTrustMapper.selectOne(any())).thenReturn(exists);

        assertTrue(service.rememberToolForAgent(1L, 9L, "t1"));

        verify(agentToolTrustMapper, never()).insert(any(AiAgentToolTrust.class));
        verify(agentToolTrustMapper, never()).update(any(AiAgentToolTrust.class), any());
    }

    @Test
    @DisplayName("查询已记住工具回填用户昵称")
    void shouldQueryTrustedTools() {
        AiAgentToolTrust trust = new AiAgentToolTrust();
        trust.setToolCode("t1");
        trust.setUserId(9L);
        trust.setDecision(AiAgentToolTrust.DECISION_ALLOW);
        when(agentToolTrustMapper.selectList(any())).thenReturn(List.of(trust));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(9L, "张三"));

        List<AiAgentToolTrustVo> rows = service.queryTrustedTools(1L);

        assertEquals(1, rows.size());
        assertEquals("张三", rows.get(0).getUserName());
        assertTrue(service.queryTrustedTools(null).isEmpty());
    }

    @Test
    @DisplayName("查询用户已记住的工具编码集合")
    void shouldQueryTrustedToolCodes() {
        AiAgentToolTrust trust = new AiAgentToolTrust();
        trust.setToolCode("t1");
        AiAgentToolTrust blank = new AiAgentToolTrust();
        blank.setToolCode(" ");
        when(agentToolTrustMapper.selectList(any())).thenReturn(List.of(trust, blank));

        assertEquals(Set.of("t1"), service.trustedToolCodes(1L, 9L));
        assertTrue(service.trustedToolCodes(null, 9L).isEmpty());
    }

    @Test
    @DisplayName("撤销信任决策")
    void shouldRevokeTrustedTool() {
        assertFalse(service.revokeTrustedTool(null, 9L, "t"));
        when(agentToolTrustMapper.delete(any())).thenReturn(1);
        assertTrue(service.revokeTrustedTool(1L, 9L, "t1"));
    }

    private static AiEntryAgent entry(Long id, String status) {
        AiEntryAgent entry = new AiEntryAgent();
        entry.setId(id);
        entry.setAgentName("小Z");
        entry.setAgentCode("entry_x");
        entry.setStatus(status);
        entry.setMaxIters(10);
        return entry;
    }

    private static AiModelConfig chatModel(Long id, String name) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setConfigName(name);
        model.setModelType("chat");
        model.setStatus("0");
        return model;
    }

    private static AiExpertAgentBo expertBo(String code, Long modelId) {
        AiExpertAgentBo bo = new AiExpertAgentBo();
        bo.setAgentCode(code);
        bo.setAgentName("专家");
        bo.setModelId(modelId);
        bo.setToolBindings(List.of());
        bo.setKbIds(List.of());
        bo.setSkillIds(List.of());
        bo.setSubagentIds(List.of());
        return bo;
    }

}