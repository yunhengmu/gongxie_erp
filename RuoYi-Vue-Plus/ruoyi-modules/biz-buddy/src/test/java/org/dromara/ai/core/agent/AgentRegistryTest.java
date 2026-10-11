package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.tools.GetCurrentTimeTool;
import org.dromara.ai.core.agent.tools.GetCurrentUserTool;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiEntryAgent;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.mapper.AiAgentKbMapper;
import org.dromara.ai.mapper.AiAgentToolMapper;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiSubagentService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRegistry} 单元测试（第 1 圈：权限上下文与快照；第 2 圈补强：{@code get} / {@code getExpert} 装配主流程）。
 *
 * <p>覆盖 {@code permissionContextOf*} 与 {@code snapshotOf*} 两类方法：工具决策合并优先级、
 * 专家模式差异、内置只读放行、子智能体协调工具放行、入口路由放行、参数校验与未装配返回 null。</p>
 *
 * <p>第 2 圈在保留上述用例的前提下追加 {@code get} / {@code getExpert}：参数校验、装配前置打桩、
 * 缓存命中 / 指纹变化重建 / 会话资源集签名变化丢弃、{@code get} 与 {@code getExpert} 缓存键隔离、
 * 以及装配入口的早退异常。装配体 {@code HarnessAgent} 的构建会做真实文件系统 / 框架装配，
 * 故不触发真实 build——缓存项经反射注入，重建路径在构建 {@code HarnessAgent} 之前即中止。</p>
 *
 * <p>每个用例由 {@link BeforeEach} 新建 {@link AgentRegistry} 实例，避免其
 * {@code cache} / {@code sessionSignature} 实例字段跨用例污染。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("智能体装配注册表：权限上下文与快照")
class AgentRegistryTest {

    private static final long EXPERT_ID = 1L;
    private static final long ENTRY_ID = 3L;

    @Mock
    private AiAgentToolMapper agentToolMapper;
    @Mock
    private AiAgentKbMapper agentKbMapper;
    @Mock
    private IAiToolService toolService;
    @Mock
    private IAiSkillService skillService;
    @Mock
    private IAiSubagentService subagentService;
    @Mock
    private AiToolFactory toolFactory;
    @Mock
    private AgentStateStore stateStore;
    @Mock
    private AgentSkillRepository aiSkillRepository;
    @Mock
    private AiProperties aiProperties;
    @Mock
    private AiWriteDedupStore writeDedupStore;
    @Mock
    private AiPermissionModeStore permissionModeStore;
    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;
    @Mock
    private IAiMcpClientService mcpClientService;
    @Mock
    private IAiSessionResourceService sessionResourceService;
    @Mock
    private AgentModelFactory modelFactory;
    @Mock
    private AgentFingerprintCalculator fingerprintCalculator;
    @Mock
    private AgentSnapshotFactory snapshotFactory;
    @Mock
    private SubagentAssembler subagentAssembler;
    @Mock
    private AiRouteOrchestrator routeOrchestrator;

    private AgentRegistry registry;

    @BeforeAll
    static void setUpClass() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        registry = newRegistry(assembler());
    }

    /**
     * 构造共享同一批 {@code @Mock} 的真实装配器：注册表把装配职责委托给它，
     * 故装配主流程用例（模型缺失、指纹重建）仍走真实装配路径。
     */
    private AgentAssembler assembler() {
        return new AgentAssembler(modelFactory, aiProperties, stateStore, toolFactory, writeDedupStore,
            mcpClientService, aiSkillRepository, permissionModeStore, snapshotFactory, subagentAssembler,
            routeOrchestrator);
    }

    private AgentRegistry newRegistry(AgentAssembler assembler) {
        return new AgentRegistry(agentToolMapper, agentKbMapper,
            mock(org.dromara.ai.mapper.AiEntryAgentMapper.class), toolService, skillService, subagentService,
            permissionModeStore, runtimeConfigStore, mcpClientService, sessionResourceService, modelFactory,
            fingerprintCalculator, assembler);
    }

    // ---------- fixtures ----------

    private static AiExpertAgent expert(Long id) {
        AiExpertAgent def = new AiExpertAgent();
        def.setId(id);
        def.setAgentCode("expert_" + id);
        return def;
    }

    private static AiEntryAgent entry(Long id) {
        AiEntryAgent def = new AiEntryAgent();
        def.setId(id);
        def.setAgentCode("entry_" + id);
        return def;
    }

    private static AiAgentTool bind(String toolCode, String decision) {
        AiAgentTool bind = new AiAgentTool();
        bind.setToolCode(toolCode);
        bind.setDecision(decision);
        return bind;
    }

    private static AiSessionResourceSet resourceSet(Map<String, String> pack, Map<String, String> direct) {
        return new AiSessionResourceSet("sig", "", pack, List.of(), List.of(), direct, List.of(), List.of(), null);
    }

    private void stubBindings(List<AiAgentTool> bindings) {
        when(agentToolMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(bindings);
    }

    private void stubEmptyMcp() {
        when(mcpClientService.resolveForAssembly(anyList())).thenReturn(McpClientAssembly.empty());
    }

    /**
     * 构造 {@code get(...)} 不触发真实装配的注册表实例：用于验证「未装配 → 快照返回 null」。
     * 匿名子类覆写两参 {@code get}，其余行为与真实实例一致（{@code cache} 为空）。
     */
    private AgentRegistry registryWithoutAssembly() {
        return new AgentRegistry(agentToolMapper, agentKbMapper,
            mock(org.dromara.ai.mapper.AiEntryAgentMapper.class), toolService, skillService, subagentService,
            permissionModeStore, runtimeConfigStore, mcpClientService, sessionResourceService, modelFactory,
            fingerprintCalculator, assembler()) {
            @Override
            public HarnessAgent get(AiAgent definition, AiSessionResourceSet resources) {
                return null;
            }
        };
    }

    // ---------- get / getExpert：装配前置打桩与缓存注入 ----------

    private static final String HOLDER_CLASS = "org.dromara.ai.core.agent.AgentRegistry$AgentHolder";

    /**
     * 本次装配指纹（普通模式）：与 {@link #stubAssemblyPreamble()} 中 {@code buildFingerprint} 的返回拼接一致。
     */
    private static final String NORMAL_FINGERPRINT = "FP|expert=false";

    /**
     * 本次装配指纹（专家模式）。
     */
    private static final String EXPERT_FINGERPRINT = "FP|expert=true";

    /**
     * 打桩 {@code get} / {@code getExpert} 走到缓存判定前所需的外部依赖，使方法可脱离真实装配运行：
     * 空绑定、空知识库、空 MCP、给定指纹。装配体（HarnessAgent）本身不构造。
     */
    private void stubAssemblyPreamble() {
        stubBindings(List.of());
        when(agentKbMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        stubEmptyMcp();
        when(fingerprintCalculator.modelSignature(any(AiAgent.class))).thenReturn("model-sig");
        when(fingerprintCalculator.buildFingerprint(any(), anyList(), anyList(), any(), any(), any(),
            any(), any(), anyList(), any(), anyList(), any())).thenReturn("FP");
    }

    /**
     * 向私有 {@code cache} 注入一条装配缓存项（与 {@link AgentRegistry} 内部键 / 指纹契约一致），
     * 用于在「不构造真实 HarnessAgent」的前提下覆盖缓存命中 / 重建 / 丢弃等判定分支。
     */
    @SuppressWarnings("unchecked")
    private void seedCache(Long agentId, boolean expertMode, String signature,
                           HarnessAgent agent, String fingerprint) throws Exception {
        Field cacheField = AgentRegistry.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(registry);
        Class<?> holderType = Class.forName(HOLDER_CLASS);
        Constructor<?> ctor = holderType.getDeclaredConstructor(
            Long.class, HarnessAgent.class, String.class, AgentSnapshot.class);
        ctor.setAccessible(true);
        cache.put(cacheKey(agentId, expertMode, signature), ctor.newInstance(agentId, agent, fingerprint, null));
    }

    /**
     * 向私有 {@code sessionSignature} 预置「会话 → 上次资源集签名」记录。
     */
    @SuppressWarnings("unchecked")
    private void seedSessionSignature(Long agentId, Long sessionId, String signature) throws Exception {
        Field field = AgentRegistry.class.getDeclaredField("sessionSignature");
        field.setAccessible(true);
        ((Map<String, String>) field.get(registry)).put(agentId + "#" + sessionId, signature);
    }

    /**
     * 复刻 {@code AgentRegistry#cacheKey} 的键构造规则。
     */
    private static String cacheKey(Long agentId, boolean expertMode, String signature) {
        String sig = signature == null ? "" : signature;
        return expertMode
            ? agentId + "#" + AgentRegistry.EXPERT_CACHE_SUFFIX + "#R:" + sig
            : agentId + "#R:" + sig;
    }

    // ---------- permissionContextOf(AiAgent, AiSessionResourceSet) ----------

    @Test
    @DisplayName("工具决策合并优先级：直接选中 > 包内 > 绑定，且内置只读默认放行")
    void shouldBuildContextWithMergePriority() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of(bind("t_bind", AiAgentTool.DECISION_ASK),
            bind("t_both", AiAgentTool.DECISION_DENY)));
        stubEmptyMcp();
        when(subagentService.queryBoundEnabledSubagents(eq(EXPERT_ID))).thenReturn(List.of());

        // pack: t_both=需确认, t_pack=允许；direct: t_both=允许, t_direct=禁止
        AiSessionResourceSet res = resourceSet(Map.of("t_both", AiAgentTool.DECISION_ASK,
            "t_pack", AiAgentTool.DECISION_ALLOW),
            Map.of("t_both", AiAgentTool.DECISION_ALLOW, "t_direct", AiAgentTool.DECISION_DENY));

        PermissionContextState ctx = registry.permissionContextOf(expert(EXPERT_ID), res);

        assertEquals(PermissionMode.DEFAULT, ctx.getMode());
        // 仅绑定：按绑定决策（需确认）
        assertTrue(ctx.getAskRules().containsKey("t_bind"));
        assertEquals(AgentRegistry.SOURCE_AGENT_TOOL, ctx.getAskRules().get("t_bind").get(0).source());
        // 绑定 + 包内 + 直接选中：直接选中优先（允许）
        assertTrue(ctx.getAllowRules().containsKey("t_both"));
        assertEquals(AgentRegistry.SOURCE_SESSION, ctx.getAllowRules().get("t_both").get(0).source());
        // 仅包内：包内决策（允许）来源 scenario_pack
        assertTrue(ctx.getAllowRules().containsKey("t_pack"));
        assertEquals(AgentRegistry.SOURCE_PACK, ctx.getAllowRules().get("t_pack").get(0).source());
        // 仅直接选中：禁止，来源 session
        assertTrue(ctx.getDenyRules().containsKey("t_direct"));
        assertEquals(AgentRegistry.SOURCE_SESSION, ctx.getDenyRules().get("t_direct").get(0).source());
        // 未绑定的平台内置只读工具默认放行
        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_READONLY_TOOL_CODES));
        assertEquals(AgentRegistry.SOURCE_BUILTIN,
            ctx.getAllowRules().get(GetCurrentTimeTool.TOOL_NAME).get(0).source());
    }

    @Test
    @DisplayName("资源集为 null 视为空集")
    void shouldTreatNullResourceSetAsEmpty() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of());
        stubEmptyMcp();
        when(subagentService.queryBoundEnabledSubagents(eq(EXPERT_ID))).thenReturn(List.of());

        PermissionContextState ctx = registry.permissionContextOf(expert(EXPERT_ID), (AiSessionResourceSet) null);

        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_READONLY_TOOL_CODES));
    }

    @Test
    @DisplayName("显式绑定的内置只读工具不再套用默认放行")
    void shouldNotApplyBuiltinDefaultWhenExplicitlyBound() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of(bind(GetCurrentTimeTool.TOOL_NAME, AiAgentTool.DECISION_DENY)));
        stubEmptyMcp();
        when(subagentService.queryBoundEnabledSubagents(eq(EXPERT_ID))).thenReturn(List.of());

        PermissionContextState ctx = registry.permissionContextOf(expert(EXPERT_ID),
            AiSessionResourceSet.empty());

        assertTrue(ctx.getDenyRules().containsKey(GetCurrentTimeTool.TOOL_NAME));
        assertFalse(ctx.getAllowRules().containsKey(GetCurrentTimeTool.TOOL_NAME));
        // 其余内置只读仍默认放行
        assertTrue(ctx.getAllowRules().containsKey(GetCurrentUserTool.TOOL_NAME));
    }

    @Test
    @DisplayName("绑定启用子智能体时放行编排协调工具")
    void shouldAllowSubagentCoordinationToolsWhenBound() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of());
        stubEmptyMcp();
        when(subagentService.queryBoundEnabledSubagents(eq(EXPERT_ID)))
            .thenReturn(List.of(new AiSubagent()));

        PermissionContextState ctx = registry.permissionContextOf(expert(EXPERT_ID),
            AiSessionResourceSet.empty());

        assertTrue(ctx.getAllowRules().containsKey("agent_spawn"));
        assertEquals(AgentRegistry.SOURCE_SUBAGENT, ctx.getAllowRules().get("agent_spawn").get(0).source());
        assertTrue(ctx.getAllowRules().containsKey("wait_async_results"));
    }

    @Test
    @DisplayName("入口智能体放行 route_decision")
    void shouldAllowRouteDecisionForEntryAgent() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of());
        stubEmptyMcp();
        when(subagentService.queryBoundEnabledSubagents(eq(ENTRY_ID))).thenReturn(List.of());

        PermissionContextState ctx = registry.permissionContextOf(entry(ENTRY_ID),
            AiSessionResourceSet.empty());

        assertTrue(ctx.getAllowRules().containsKey(RouteDecisionTool.TOOL_NAME));
        assertEquals(AgentRegistry.SOURCE_ROUTE,
            ctx.getAllowRules().get(RouteDecisionTool.TOOL_NAME).get(0).source());
    }

    @Test
    @DisplayName("MCP 工具决策以场景包来源并入")
    void shouldAddMcpDecisionsWithPackSource() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of());
        when(mcpClientService.resolveForAssembly(anyList())).thenReturn(
            new McpClientAssembly(List.of(), List.of("mcp_a"), Map.of(),
                Map.of("mcp_a", AiAgentTool.DECISION_ASK), "sig"));
        when(subagentService.queryBoundEnabledSubagents(eq(EXPERT_ID))).thenReturn(List.of());

        PermissionContextState ctx = registry.permissionContextOf(expert(EXPERT_ID),
            AiSessionResourceSet.empty());

        assertTrue(ctx.getAskRules().containsKey("mcp_a"));
        assertEquals(AgentRegistry.SOURCE_PACK, ctx.getAskRules().get("mcp_a").get(0).source());
    }

    @Test
    @DisplayName("场景包重载走 sessionResourceService.ofSinglePack")
    void shouldDelegateScenarioPackOverloadToSinglePack() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of());
        stubEmptyMcp();
        when(subagentService.queryBoundEnabledSubagents(eq(EXPERT_ID))).thenReturn(List.of());

        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(9L);
        when(sessionResourceService.ofSinglePack(pack)).thenReturn(
            resourceSet(Map.of("t_pack", AiAgentTool.DECISION_ALLOW), Map.of()));

        PermissionContextState ctx = registry.permissionContextOf(expert(EXPERT_ID), pack);

        assertTrue(ctx.getAllowRules().containsKey("t_pack"));
        assertEquals(AgentRegistry.SOURCE_PACK, ctx.getAllowRules().get("t_pack").get(0).source());
        verify(sessionResourceService).ofSinglePack(pack);
    }

    @Test
    @DisplayName("定义为 null 抛业务异常")
    void shouldThrowWhenDefinitionNull() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.permissionContextOf(null, AiSessionResourceSet.empty()));
        assertEquals("智能体定义不存在", ex.getMessage());
    }

    @Test
    @DisplayName("定义主键为 null 抛业务异常")
    void shouldThrowWhenDefinitionIdNull() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.permissionContextOf(expert(null), AiSessionResourceSet.empty()));
        assertEquals("智能体定义不存在", ex.getMessage());
    }

    // ---------- permissionContextOfExpert ----------

    @Test
    @DisplayName("专家模式：即使入口定义也不放行 route_decision，且不查询子智能体")
    void shouldBuildExpertContextWithoutSubagentsOrRoute() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DONT_ASK);
        stubBindings(List.of());
        stubEmptyMcp();

        PermissionContextState ctx = registry.permissionContextOfExpert(entry(ENTRY_ID),
            AiSessionResourceSet.empty());

        assertEquals(PermissionMode.DONT_ASK, ctx.getMode());
        assertFalse(ctx.getAllowRules().containsKey(RouteDecisionTool.TOOL_NAME));
        assertFalse(ctx.getAllowRules().containsKey("agent_spawn"));
        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_READONLY_TOOL_CODES));
        verify(subagentService, never()).queryBoundEnabledSubagents(any());
    }

    @Test
    @DisplayName("专家模式无资源集重载：委托空资源集")
    void shouldBuildExpertContextForNoResources() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        stubBindings(List.of());
        stubEmptyMcp();

        PermissionContextState ctx = registry.permissionContextOfExpert(expert(EXPERT_ID));

        assertTrue(ctx.getAllowRules().keySet().containsAll(AgentRegistry.BUILTIN_READONLY_TOOL_CODES));
        assertFalse(ctx.getAllowRules().containsKey(RouteDecisionTool.TOOL_NAME));
    }

    @Test
    @DisplayName("专家模式定义为 null 抛业务异常")
    void shouldThrowWhenExpertDefinitionNull() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.permissionContextOfExpert(null, AiSessionResourceSet.empty()));
        assertEquals("智能体定义不存在", ex.getMessage());
    }

    // ---------- snapshotOf ----------

    @Test
    @DisplayName("快照：定义为 null 返回 null")
    void shouldReturnNullSnapshotWhenDefinitionNull() {
        AgentRegistry noAssembly = registryWithoutAssembly();
        assertNull(noAssembly.snapshotOf(null, AiSessionResourceSet.empty()));
        assertNull(noAssembly.snapshotOf(null));
    }

    @Test
    @DisplayName("快照：定义主键为 null 返回 null")
    void shouldReturnNullSnapshotWhenIdNull() {
        AgentRegistry noAssembly = registryWithoutAssembly();
        AiExpertAgent def = expert(null);
        assertNull(noAssembly.snapshotOf(def, AiSessionResourceSet.empty()));
        assertNull(noAssembly.snapshotOf(def));
    }

    @Test
    @DisplayName("快照：未装配返回 null")
    void shouldReturnNullSnapshotWhenNotAssembled() {
        AgentRegistry noAssembly = registryWithoutAssembly();
        AiExpertAgent def = expert(EXPERT_ID);
        assertNull(noAssembly.snapshotOf(def, AiSessionResourceSet.empty()));
        assertNull(noAssembly.snapshotOf(def));
    }

    @Test
    @DisplayName("快照：场景包重载走 sessionResourceService.ofSinglePack")
    void shouldDelegateScenarioPackSnapshotOverload() {
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(9L);
        when(sessionResourceService.ofSinglePack(pack)).thenReturn(AiSessionResourceSet.empty());
        AgentRegistry noAssembly = registryWithoutAssembly();

        assertNull(noAssembly.snapshotOf(expert(EXPERT_ID), pack));
        verify(sessionResourceService).ofSinglePack(pack);
    }

    // ---------- get / getExpert：参数校验 ----------

    @Test
    @DisplayName("get：定义为 null 抛业务异常")
    void shouldThrowWhenGetDefinitionNull() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.get((AiAgent) null));
        assertEquals("智能体定义不存在", ex.getMessage());
    }

    @Test
    @DisplayName("get：定义主键为 null 抛业务异常")
    void shouldThrowWhenGetDefinitionIdNull() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.get(expert(null), AiSessionResourceSet.empty()));
        assertEquals("智能体定义不存在", ex.getMessage());
    }

    @Test
    @DisplayName("getExpert：定义为 null / 主键为 null 抛业务异常")
    void shouldThrowWhenGetExpertDefinitionInvalid() {
        ServiceException nullDef = assertThrows(ServiceException.class,
            () -> registry.getExpert(null, AiSessionResourceSet.empty()));
        assertEquals("智能体定义不存在", nullDef.getMessage());

        ServiceException nullId = assertThrows(ServiceException.class,
            () -> registry.getExpert(expert(null)));
        assertEquals("智能体定义不存在", nullId.getMessage());
    }

    // ---------- get / getExpert：装配主体（装配前置 + 缓存判定） ----------

    @Test
    @DisplayName("get：对话模型缺失时装配入口即抛业务异常")
    void shouldThrowWhenChatModelMissingDuringAssembly() {
        stubAssemblyPreamble();
        when(modelFactory.resolveChatModel(any()))
            .thenThrow(new ServiceException("智能体绑定的对话模型不存在或已被删除"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.get(expert(EXPERT_ID), AiSessionResourceSet.empty()));

        assertEquals("智能体绑定的对话模型不存在或已被删除", ex.getMessage());
    }

    @Test
    @DisplayName("get：指纹一致命中缓存，重复取用返回同一实例且不再重建")
    void shouldReturnCachedInstanceOnFingerprintMatch() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent cached = mock(HarnessAgent.class);
        seedCache(EXPERT_ID, false, AiSessionResourceSet.empty().signature(), cached, NORMAL_FINGERPRINT);

        AiExpertAgent def = expert(EXPERT_ID);
        HarnessAgent first = registry.get(def);
        HarnessAgent second = registry.get(def);

        assertSame(cached, first);
        assertSame(cached, second);
        verify(modelFactory, never()).resolveChatModel(any());
        verify(cached, never()).close();
    }

    @Test
    @DisplayName("getExpert：指纹一致命中专家缓存")
    void shouldReturnCachedExpertInstanceOnFingerprintMatch() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent cached = mock(HarnessAgent.class);
        seedCache(EXPERT_ID, true, AiSessionResourceSet.empty().signature(), cached, EXPERT_FINGERPRINT);

        HarnessAgent result = registry.getExpert(expert(EXPERT_ID));

        assertSame(cached, result);
        verify(modelFactory, never()).resolveChatModel(any());
        verify(cached, never()).close();
    }

    @Test
    @DisplayName("get 与 getExpert 使用不同缓存键：两种实例互不覆盖")
    void shouldKeepGetAndExpertCacheEntriesSeparate() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent normal = mock(HarnessAgent.class);
        HarnessAgent expertVer = mock(HarnessAgent.class);
        String sig = AiSessionResourceSet.empty().signature();
        seedCache(EXPERT_ID, false, sig, normal, NORMAL_FINGERPRINT);
        seedCache(EXPERT_ID, true, sig, expertVer, EXPERT_FINGERPRINT);

        AiExpertAgent def = expert(EXPERT_ID);
        assertSame(normal, registry.get(def, AiSessionResourceSet.empty()));
        assertSame(expertVer, registry.getExpert(def, AiSessionResourceSet.empty()));
        // 再取一次仍各自命中，说明两种键互不覆盖
        assertSame(normal, registry.get(def, AiSessionResourceSet.empty()));
        assertSame(expertVer, registry.getExpert(def, AiSessionResourceSet.empty()));
        assertNotSame(normal, expertVer);
    }

    @Test
    @DisplayName("get：指纹变化时关闭旧实例并重建（重建期早退异常）")
    void shouldRebuildAndCloseStaleWhenFingerprintChanges() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent stale = mock(HarnessAgent.class);
        seedCache(EXPERT_ID, false, AiSessionResourceSet.empty().signature(), stale, "OLD|expert=false");
        when(modelFactory.resolveChatModel(any())).thenReturn(mock(AiModelConfig.class));
        when(modelFactory.buildChatModel(any())).thenThrow(new ServiceException("模型构造失败"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> registry.get(expert(EXPERT_ID)));

        assertEquals("模型构造失败", ex.getMessage());
        verify(stale).close();
    }

    @Test
    @DisplayName("get：会话内资源组合变化时丢弃并关闭旧实例")
    void shouldDiscardStaleInstanceWhenSessionSignatureChanges() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent stale = mock(HarnessAgent.class);
        seedCache(EXPERT_ID, false, "sig2", stale, NORMAL_FINGERPRINT);
        seedSessionSignature(EXPERT_ID, 77L, "sig1");
        when(modelFactory.resolveChatModel(any())).thenThrow(new ServiceException("模型缺失"));

        AiSessionResourceSet res = new AiSessionResourceSet("sig2", "",
            Map.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), 77L);

        assertThrows(ServiceException.class, () -> registry.get(expert(EXPERT_ID), res));
        verify(stale).close();
    }

    @Test
    @DisplayName("get：会话首次装配记录签名且不丢弃缓存实例")
    void shouldNotDiscardWhenSessionFirstSeen() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent cached = mock(HarnessAgent.class);
        seedCache(EXPERT_ID, false, "sig1", cached, NORMAL_FINGERPRINT);

        AiSessionResourceSet res = new AiSessionResourceSet("sig1", "",
            Map.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), 88L);

        HarnessAgent result = registry.get(expert(EXPERT_ID), res);

        assertSame(cached, result);
        verify(cached, never()).close();
        verify(modelFactory, never()).resolveChatModel(any());
    }

    @Test
    @DisplayName("get：场景包重载委托 ofSinglePack 后命中缓存")
    void shouldDelegateScenarioPackGetOverload() throws Exception {
        stubAssemblyPreamble();
        HarnessAgent cached = mock(HarnessAgent.class);
        seedCache(EXPERT_ID, false, AiSessionResourceSet.empty().signature(), cached, NORMAL_FINGERPRINT);
        AiScenarioPack pack = new AiScenarioPack();
        pack.setId(9L);
        when(sessionResourceService.ofSinglePack(pack)).thenReturn(AiSessionResourceSet.empty());

        HarnessAgent result = registry.get(expert(EXPERT_ID), pack);

        assertSame(cached, result);
        verify(sessionResourceService).ofSinglePack(pack);
    }

}
