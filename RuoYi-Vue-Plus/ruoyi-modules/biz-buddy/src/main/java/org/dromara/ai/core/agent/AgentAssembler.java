package org.dromara.ai.core.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.mcp.GuardingMcpClientWrapper;
import org.dromara.ai.core.memory.AiMemoryDeclaredStore;
import org.dromara.ai.core.memory.AiMemoryPrompts;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体装配器：把「定义 + 已解析的工具 / 知识库 / 技能 / 子智能体 / 运行期策略 / MCP 连接」
 * 装配为框架 {@link HarnessAgent} 并生成执行期快照。
 *
 * <p>从 {@link AgentRegistry} 抽出：注册表负责「按缓存与指纹决定何时装配、装配结果如何失效」，
 * 本类只负责「给定入参如何把实例装配出来」，不持有装配缓存。工具可见性、决策合并、指纹与缓存键
 * 仍由注册表计算后传入，故本类不做任何查库。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AgentAssembler {

    /**
     * 技能内置工具名（由框架 DynamicSkillMiddleware 注册，需显式放进 ToolsConfig 白名单，
     * 否则会被 allow 名单裁掉）
     */
    private static final List<String> SKILL_BUILTIN_TOOLS = List.of("load_skill_through_path", "read_skill");

    /**
     * 只读记忆检索工具名（A1 POC 放行；写入类工具仍被 deny 裁掉）
     */
    private static final String MEMORY_SEARCH_TOOL = "memory_search";

    private static final String MEMORY_GET_TOOL = "memory_get";

    /**
     * 记忆写入/跨会话检索工具名：模型不得自行改写长期记忆
     */
    private static final String MEMORY_SAVE_TOOL = "memory_save";

    private static final String SESSION_SEARCH_TOOL = "session_search";

    /**
     * 专家「技能优先」纪律：装配期追加到专家提示词末尾，**不入库**。
     *
     * <p>为什么需要：专家装配本来就会注册 {@code load_skill_through_path} 并注入可用技能清单，
     * 但领域专家的提示词通篇是数据维护口径，而技能清单是框架在 system prompt 末尾追加的、
     * 位置靠后且没有强约束力，实测（2026-10-03，部门人数可视化）模型会直接用 Markdown 表格作答、
     * 自始至终不加载技能。故在专家提示词正文末尾补一段纪律，把「命中技能必须先加载」讲死。</p>
     */
    private static final String EXPERT_SKILL_DISCIPLINE = """
        【技能优先】
        提示词末尾会列出你可用的技能清单。当用户诉求命中其中某个技能时（例如要求出图 / 画成图表 / 按某格式产出），
        必须先调用 load_skill_through_path 加载该技能正文（path="SKILL.md"）读懂它的产出规格，再按正文要求用同一工具
        加载参考资料；最后严格按技能规定的产出物交付。不要用自认为够用的形式（例如纯 Markdown 表格）代替技能的产出规格。""";

    /**
     * 外部 MCP 工具注册（含连接初始化）的超时上限：避免单个不可达的 Server 拖住整个智能体装配
     */
    private static final Duration MCP_REGISTER_TIMEOUT = Duration.ofSeconds(20);

    private final AgentModelFactory modelFactory;

    private final AiProperties aiProperties;

    /**
     * 运行期配置中心（提示词注入防护开关按运行期配置实时读取，见 {@link PromptHardeningMiddleware}）
     */
    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AgentStateStore stateStore;

    private final AiToolFactory toolFactory;

    private final AiWriteDedupStore writeDedupStore;

    /**
     * 显式记忆存储（平台托管模式下由注入中间件读取）
     */
    private final AiMemoryDeclaredStore memoryDeclaredStore;

    /**
     * 安全守卫：给业务工具包一层「结果脱敏」，使喂给模型的结果不含 PII/命中词
     * （见 {@link ToolResultGuardTool}）；外部 MCP 工具拿不到工具包装点，改在客户端层拦截
     * （见 {@link GuardingMcpClientWrapper}）。此前只脱敏了落库与前端两份副本。
     */
    private final AiSafetyGuard safetyGuard;

    private final IAiMcpClientService mcpClientService;

    private final AgentSkillRepository aiSkillRepository;

    private final AiPermissionModeStore permissionModeStore;

    private final AgentSnapshotFactory snapshotFactory;

    private final SubagentAssembler subagentAssembler;

    /**
     * 专家编排器（REQ-AI-30）：仅供构造 {@link RouteDecisionTool} 使用。
     *
     * <p>用 {@code @Lazy} 注入以打破「注册表 → 装配器 → 编排器 → … → 智能体服务 → 注册表」的构造期循环依赖：
     * 编排器装配专家要经 {@link AgentRegistry}（其依赖本装配器），而装配器构造协议工具要拿编排器。</p>
     */
    private final AiRouteOrchestrator routeOrchestrator;

    /**
     * @param modelFactory       模型构造工厂
     * @param aiProperties       平台配置
     * @param runtimeConfigStore 运行期配置中心
     * @param stateStore         AgentScope 状态存储
     * @param toolFactory        业务工具工厂（implName → AgentTool）
     * @param writeDedupStore    专家写工具短期去重
     * @param memoryDeclaredStore 显式记忆存储（平台托管注入）
     * @param safetyGuard        安全守卫（工具结果喂模型前脱敏）
     * @param mcpClientService   外部 MCP 连接（REQ-AI-16）
     * @param aiSkillRepository  技能仓库（本项目数据库，平台唯一技能来源）
     * @param permissionModeStore 全局权限模式
     * @param snapshotFactory    装配快照工厂
     * @param subagentAssembler  子智能体装配支撑
     * @param routeOrchestrator  专家编排器（REQ-AI-30，延迟注入以打破循环依赖）
     */
    public AgentAssembler(AgentModelFactory modelFactory,
                          AiProperties aiProperties,
                          AiRuntimeConfigStore runtimeConfigStore,
                          @Qualifier("aiAgentStateStore") AgentStateStore stateStore,
                          AiToolFactory toolFactory,
                          AiWriteDedupStore writeDedupStore,
                          AiMemoryDeclaredStore memoryDeclaredStore,
                          AiSafetyGuard safetyGuard,
                          IAiMcpClientService mcpClientService,
                          AgentSkillRepository aiSkillRepository,
                          AiPermissionModeStore permissionModeStore,
                          AgentSnapshotFactory snapshotFactory,
                          SubagentAssembler subagentAssembler,
                          @Lazy AiRouteOrchestrator routeOrchestrator) {
        this.modelFactory = modelFactory;
        this.aiProperties = aiProperties;
        this.runtimeConfigStore = runtimeConfigStore;
        this.stateStore = stateStore;
        this.toolFactory = toolFactory;
        this.writeDedupStore = writeDedupStore;
        this.memoryDeclaredStore = memoryDeclaredStore;
        this.safetyGuard = safetyGuard;
        this.mcpClientService = mcpClientService;
        this.aiSkillRepository = aiSkillRepository;
        this.permissionModeStore = permissionModeStore;
        this.snapshotFactory = snapshotFactory;
        this.subagentAssembler = subagentAssembler;
        this.routeOrchestrator = routeOrchestrator;
    }

    /**
     * 装配 HarnessAgent。
     *
     * @param definition   智能体定义
     * @param enabledTools 启用中的业务工具实体（已剔除被禁止的工具；含资源集内叠加的工具）
     * @param kbIds        绑定的知识库主键
     * @param skillNames   生效技能过滤键
     * @param bindings     智能体侧工具绑定（含授权决策）
     * @param resources    会话级资源集（REQ-AI-32；含包内与直接选中的工具 / 技能 / MCP 工具与档位）
     * @param subagents    绑定的启用中子智能体（REQ-AI-04）
     * @param compactionPolicy 上下文压缩策略
     * @param planPolicy   计划模式与任务清单配置
     * @param memoryPolicy 长期记忆配置
     * @param workspacePolicy 工作区上下文配置
     * @param mcpAssembly   外部 MCP 连接装配结果（REQ-AI-16；已按资源集收敛）
     * @param expertMode    是否专家执行装配（子智能体已在外层关闭；专家直答后专家侧不注册任何协议工具）
     * @param workspaceCode 工作区命名空间编码（专家执行装配取入口智能体编码，以共享同一份用户长期记忆）
     * @return 装配结果（智能体实例 + 执行期快照）
     */
    AssembledAgent assemble(AiAgent definition, List<AiTool> enabledTools, List<Long> kbIds,
                            List<String> skillNames, List<AiAgentTool> bindings,
                            AiSessionResourceSet resources, List<AiSubagent> subagents,
                            AiCompactionConfigBo compactionPolicy,
                            AiPlanConfigBo planPolicy, AiMemoryConfigBo memoryPolicy,
                            AiWorkspaceConfigBo workspacePolicy,
                            McpClientAssembly mcpAssembly, boolean expertMode, String workspaceCode) {
        AiModelConfig modelConfig = modelFactory.resolveChatModel(definition.getModelId());
        Model model = modelFactory.buildChatModel(modelConfig);
        Toolkit toolkit = new Toolkit();
        List<String> registeredCodes = new ArrayList<>(
            registerTools(toolkit, enabledTools, kbIds, expertMode));
        // 外部 MCP 工具（REQ-AI-16）注册进同一工具箱（仅资源集内的工具）
        registerMcpClients(toolkit, mcpAssembly, definition);
        // 编排协议工具（REQ-AI-30）：只对入口智能体注册
        registeredCodes.addAll(registerProtocolTools(toolkit, definition));
        // 子智能体可继承的工具池＝父级「实际注册成功的只读业务工具」；
        // 写入类工具永不下放（子智能体不继承父级 allow / ask 规则，没有人工确认闸门，见 docs/00 第 42 项）
        List<String> subagentToolPool = readOnlyRegisteredCodes(enabledTools, registeredCodes);
        int maxIters = definition.getMaxIters() == null
            ? aiProperties.getChat().getDefaultMaxIters() : definition.getMaxIters();
        log.info("装配智能体：code={}, model={}, tools={}, kbIds={}, maxIters={}, decisions={}, "
                + "resourceTools={}, mcpTools={}, resourceSig={}",
            definition.getAgentCode(), modelConfig.getModelCode(), registeredCodes, kbIds, maxIters,
            AgentPermissionContextFactory.renderDecisions(
                AgentPermissionContextFactory.mergeToolDecisions(bindings, resources.mergedToolDecisions())),
            resources.mergedToolDecisions(), mcpAssembly.toolNames(), resources.signature());
        // 平台托管模式：关闭框架记忆 hooks 与记忆工具，由平台注入/抽取/合并接管
        boolean platformMemory =
            memoryPolicy.isEnabled() && "platform".equals(memoryPolicy.getPipelineMode());
        // 只读记忆检索 POC：开启后注册框架记忆工具，再由白名单只放行 memory_search / memory_get；
        // 平台托管模式下框架记忆工具恒不注册（后续由平台侧检索工具替代）
        boolean memoryReadTools = memoryPolicy.isEnabled()
            && aiProperties.getChat().isMemoryReadToolsEnabled() && !platformMemory;
        HarnessAgent.Builder builder = HarnessAgent.builder()
            .name(definition.getAgentCode())
            .description(StringUtils.isBlank(definition.getDescription())
                ? definition.getAgentName() : definition.getDescription())
            .sysPrompt(resolveSysPrompt(definition, skillNames, expertMode))
            .model(model)
            .toolkit(toolkit)
            .stateStore(stateStore)
            .workspace(resolveWorkspace(workspaceCode))
            .maxIters(maxIters)
            .toolsConfig(buildToolsConfig(registeredCodes, skillNames, mcpAssembly, memoryReadTools))
            // 工具授权：按「智能体 × 工具」「场景包 × 工具」与「会话直接选中」的合并决策生成 allow / ask / deny 规则
            //（越显式越优先：直接选中 > 包内 > 智能体绑定；写工具走 ask → HITL 确认）
            .permissionContext(AgentPermissionContextFactory.buildContext(permissionModeStore.resolve(),
                bindings, resources.packToolDecisions(),
                resources.directToolDecisions(), mcpAssembly.toolDecisions(),
                !subagents.isEmpty(), definition.isEntry()))
            // 当前时间注入：平台级中间件，所有智能体自动生效；每轮现算，改开关/时区无需重建
            .middlewares(buildMiddlewares(definition, workspaceCode, platformMemory));
        if (subagents.isEmpty()) {
            // 未绑定子智能体：与既往行为完全一致（不注册 agent_spawn 等协调工具）
            builder.disableSubagents();
        } else {
            // 绑定子智能体（REQ-AI-04）：生成声明并注入模型解析器
            // （声明的 model 只能是字符串，由 modelResolver 还原为模型实例；解析失败由框架回退主模型）
            builder.subagents(subagentAssembler.buildSubagentDeclarations(subagents, definition, subagentToolPool))
                .modelResolver(modelFactory::resolveSubagentModel);
            log.info("子智能体装配：agent={}, 声明={}", definition.getAgentCode(),
                subagents.stream().map(AiSubagent::getName).toList());
        }
        // 上下文压缩（REQ-AI-21）与计划模式 / 任务清单（REQ-AI-22）按运行期策略装配
        applyCompaction(builder, definition, compactionPolicy);
        applyPlanMode(builder, planPolicy);
        // 长期记忆（REQ-AI-09）按运行期策略装配
        applyMemory(builder, definition, memoryPolicy);
        // 工作区上下文（REQ-AI-08）：AGENTS.md 由框架固定注入，附加上下文文件按运行期清单逐个加入
        applyWorkspaceContext(builder, workspacePolicy);
        log.info("运行期策略：agent={}, compaction={}(triggerMessages={}, triggerTokens={}, keepMessages={}, "
                + "flush={}, offload={}), toolResultEviction={}({}/{}), planMode={}(dir={}), "
                + "taskList={}, memory={}(maxContextTokens={}, consolidation={} tokens/{} 分钟, retention={}/{}/{} 天, "
                + "flush={}), workspace={}(additionalFiles={})",
            definition.getAgentCode(), compactionPolicy.isEnabled(), compactionPolicy.getTriggerMessages(),
            compactionPolicy.getTriggerTokens(), compactionPolicy.getKeepMessages(),
            compactionPolicy.isFlushBeforeCompact(), compactionPolicy.isOffloadBeforeCompact(),
            compactionPolicy.isToolResultEvictionEnabled(), compactionPolicy.getToolResultEvictionMaxChars(),
            compactionPolicy.getToolResultEvictionPreviewChars(), planPolicy.isEnabled(),
            planPolicy.getPlanFileDirectory(), planPolicy.isTaskListEnabled(),
            memoryPolicy.isEnabled(), memoryPolicy.getMaxContextTokens(), memoryPolicy.getConsolidationMaxTokens(),
            memoryPolicy.getConsolidationMinGapMinutes(), memoryPolicy.getDailyFileRetentionDays(),
            memoryPolicy.getArchiveRetentionDays(), memoryPolicy.getSessionRetentionDays(),
            memoryPolicy.getFlushMode(), workspacePolicy.isEnabled(),
            workspacePolicy.getAdditionalContextFiles());
        log.info("当前时间注入：agent={}, enabled={}, zone={}", definition.getAgentCode(),
            aiProperties.getChat().isCurrentTimeEnabled(), aiProperties.getChat().getCurrentTimeZone());
        HarnessAgent.Builder tail = builder;
        if (!memoryReadTools) {
            // 记忆工具（memory_search / memory_get / memory_save / session_search）全部不注册，
            // 记忆仅以「MEMORY.md 注入 + 自动抽取 + 自动合并」方式工作，保持工具集最小化
            tail = tail.disableMemoryTools();
        }
        if (skillNames.isEmpty()) {
            // 未绑定技能且无资源集技能：不注册技能内置工具、不注入技能清单（与既往行为一致）
            tail = tail.disableDynamicSkills();
        } else {
            // 有技能：接入本项目数据库技能仓库（平台只此一个来源，事务见 AgentScopeConfig#aiSkillRepository），
            // 并以白名单仅放行「绑定且启用中」的技能；
            // 提问用户维度的生效范围在运行期由 SkillFilter 覆盖（见 AiRouteSupport#runtimeSkillFilter）
            tail = tail.skillRepositories(List.of(aiSkillRepository))
                .skillFilter(SkillFilter.only(skillNames.toArray(new String[0])));
        }
        // 技能一律来自本项目数据库：关闭框架默认的工作区（文件系统）技能来源
        tail = tail.disableDefaultWorkspaceSkills();
        // 平台无沙箱：智能体只能调用登记在册的业务工具，禁用内置文件、命令与 Web 工具。
        // 内置 Web 工具（web_fetch / web_search）默认注册并可直接出网，绕过「无裸 HTTP、工具最小化、
        // 全链路审计」的平台纪律，故一并关闭；确需联网能力时应改造为受控工具后再登记。
        tail = tail.disableFilesystemTools().disableShellTool().disableWebTools();
        if (platformMemory) {
            // 平台托管：停掉框架抽取 / 合并 / 维护；同时停掉框架记忆工具。
            // 二者同时关闭后框架不再注入 <memory_context>，由 PlatformMemoryContextMiddleware 接管；
            // 工作区定义类文件仍由框架注入，故不调用 disableWorkspaceContext。
            tail = tail.disableMemoryHooks();
        } else if (!memoryPolicy.isEnabled()) {
            // 关闭记忆：停掉记忆 hooks（抽取 / 合并 / 后台维护）。
            // 记忆工具恒定关闭（disableMemoryTools），二者同时关闭后中间件即不再注入 <memory_context>，
            // 故无需连带 disableWorkspaceContext —— 否则会把 AGENTS.md 等定义类文件一并关掉（REQ-AI-08）。
            tail = tail.disableMemoryHooks();
        }
        if (!memoryPolicy.isEnabled() && !workspacePolicy.isEnabled()) {
            // 记忆与工作区上下文均关闭时，才彻底停掉 system prompt 的工作区段落注入
            tail = tail.disableWorkspaceContext();
        }
        HarnessAgent agent = tail.build();
        log.info("智能体装配完成：code={}, 生效工具={}", definition.getAgentCode(), agent.getToolkit().getToolNames());
        AgentSnapshot snapshot = snapshotFactory.buildSnapshot(definition, modelConfig, registeredCodes, kbIds,
            skillNames, subagents, bindings, compactionPolicy, planPolicy, memoryPolicy,
            workspacePolicy, mcpAssembly, maxIters);
        return new AssembledAgent(agent, snapshot);
    }

    /**
     * 构造智能体的系统提示词。
     *
     * <p>专家执行装配且装配了技能时，在库中提示词正文之后追加一段平台级「技能优先」纪律
     * （{@link #EXPERT_SKILL_DISCIPLINE}）；其余场景原样返回库中提示词。
     * 追加物只作用于装配产物、不回写数据库，故定义页看到的仍是管理员维护的原文。</p>
     *
     * @param definition 智能体定义
     * @param skillNames 生效技能过滤键（为空表示未装配技能，纪律无意义）
     * @param expertMode 是否专家执行装配
     * @return 实际下发给模型的系统提示词
     */
    static String resolveSysPrompt(AiAgent definition, List<String> skillNames, boolean expertMode) {
        String base = definition.getSysPrompt() == null ? "" : definition.getSysPrompt();
        if (!expertMode || skillNames.isEmpty()) {
            return base;
        }
        return base + "\n\n" + EXPERT_SKILL_DISCIPLINE;
    }

    /**
     * 装配长期记忆（REQ-AI-09）。
     *
     * <p>记忆按用户隔离（AgentScope 默认 {@code IsolationScope.USER}），文件位于智能体工作区下：
     * {@code MEMORY.md} 为合并后的长期记忆，{@code memory/YYYY-MM-DD.md} 为当日事实流水（只追加）。</p>
     *
     * <p>抽取与合并模型取智能体定义的「记忆抽取与合并模型」槽位（REQ-AI-06）；
     * 未配置或已失效时回退主模型。</p>
     *
     * @param builder    智能体构建器
     * @param definition 智能体定义
     * @param policy     长期记忆配置
     */
    private void applyMemory(HarnessAgent.Builder builder, AiAgent definition, AiMemoryConfigBo policy) {
        if (!policy.isEnabled()) {
            return;
        }
        MemoryConfig.Builder memory = MemoryConfig.builder()
            // 平台抽取提示词：只记个人画像与偏好，项目/知识库/工具过程不进入长期记忆
            .flushPrompt(AiMemoryPrompts.FLUSH_PROMPT)
            // 平台合并提示词：只保留个人画像，并清理历史遗留的项目 / 知识库内容
            .consolidationPrompt(AiMemoryPrompts.CONSOLIDATION_PROMPT)
            .consolidationMaxTokens(policy.getConsolidationMaxTokens())
            .consolidationMinGap(Duration.ofMinutes(policy.getConsolidationMinGapMinutes()))
            .dailyFileRetentionDays(policy.getDailyFileRetentionDays())
            .sessionRetentionDays(policy.getSessionRetentionDays())
            .flushTrigger(resolveFlushTrigger(policy));
        Model memoryModel = modelFactory.resolveMemoryModel(definition);
        if (memoryModel != null) {
            memory.model(memoryModel);
        }
        builder.memory(memory.build());
        builder.maxContextTokens(policy.getMaxContextTokens());
    }

    /**
     * 装配工作区上下文（REQ-AI-08）。
     *
     * <p>人格与行为约定文件 {@code AGENTS.md} 由框架的 {@code WorkspaceContextMiddleware} 固定读入，
     * 本方法只负责按运行期清单追加「附加上下文文件」（全文注入 system prompt）。
     * 两类文件均走框架两层读：工作区根为共享默认，用户目录（{@code <userId>/}）为按用户覆盖，
     * 由 {@code RuntimeContext.userId} 驱动，无需应用层自行拼路径。</p>
     *
     * <p>关闭本开关时不再注册附加文件；工作区段落的彻底关闭由 {@link #assemble} 统一按
     * 「工作区开关 与 记忆开关 同时关闭」判定。</p>
     *
     * @param builder 智能体构建器
     * @param policy  工作区上下文配置
     */
    private void applyWorkspaceContext(HarnessAgent.Builder builder, AiWorkspaceConfigBo policy) {
        if (!policy.isEnabled() || policy.getAdditionalContextFiles() == null) {
            return;
        }
        for (String file : policy.getAdditionalContextFiles()) {
            if (file != null && !file.isBlank()) {
                builder.additionalContextFile(file);
            }
        }
    }

    /**
     * 解析事实抽取触发策略。
     *
     * @param policy 长期记忆配置
     * @return AgentScope 触发策略
     */
    static MemoryConfig.FlushTrigger resolveFlushTrigger(AiMemoryConfigBo policy) {
        return switch (policy.getFlushMode()) {
            case "never" -> MemoryConfig.FlushTrigger.never();
            case "throttled" ->
                MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(policy.getFlushThrottleMinutes()));
            default -> MemoryConfig.FlushTrigger.always();
        };
    }

    /**
     * 装配上下文压缩与大工具结果卸载（REQ-AI-21）。
     *
     * <p>压缩摘要模型取智能体定义的「压缩摘要模型」槽位（REQ-AI-06）；未配置或已失效时回退主模型。</p>
     *
     * @param builder       智能体构建器
     * @param definition    智能体定义
     * @param policy        压缩策略
     */
    private void applyCompaction(HarnessAgent.Builder builder, AiAgent definition,
                                 AiCompactionConfigBo policy) {
        if (!policy.isEnabled()) {
            builder.disableCompaction();
        } else {
            CompactionConfig.Builder compaction = CompactionConfig.builder()
                .triggerMessages(policy.getTriggerMessages())
                .triggerTokens(policy.getTriggerTokens())
                .keepMessages(policy.getKeepMessages())
                .flushBeforeCompact(policy.isFlushBeforeCompact())
                .offloadBeforeCompact(policy.isOffloadBeforeCompact());
            Model summaryModel = modelFactory.resolveSummaryModel(definition);
            if (summaryModel != null) {
                compaction.model(summaryModel);
            }
            builder.compaction(compaction.build());
        }
        if (!policy.isToolResultEvictionEnabled()) {
            builder.disableToolResultEviction();
        } else {
            builder.toolResultEviction(ToolResultEvictionConfig.builder()
                .maxResultChars(policy.getToolResultEvictionMaxChars())
                .previewChars(policy.getToolResultEvictionPreviewChars())
                .build());
        }
    }

    /**
     * 装配计划模式与任务清单（REQ-AI-22）。
     *
     * @param builder 智能体构建器
     * @param policy  计划模式配置
     */
    private void applyPlanMode(HarnessAgent.Builder builder, AiPlanConfigBo policy) {
        if (policy.isEnabled()) {
            builder.enablePlanMode(true);
            if (StringUtils.isNotBlank(policy.getPlanFileDirectory())) {
                builder.planFileDirectory(policy.getPlanFileDirectory());
            }
        }
        if (policy.isTaskListEnabled()) {
            builder.enableTaskList(true);
        }
    }

    /**
     * 取「父级实际注册成功的只读业务工具」编码，作为子智能体可继承的工具池。
     *
     * <p>写入类工具（{@code ai_tool.tool_type = '1'}）永不下放：框架不为子智能体继承父级的
     * allow / ask 权限规则（仅继承 deny），子智能体内没有人工确认闸门，一旦下放即绕过确认直接执行。</p>
     *
     * @param enabledTools   启用中的业务工具实体
     * @param registeredCodes 实际注册成功的工具编码
     * @return 只读工具编码集合（保持注册顺序）
     */
    static List<String> readOnlyRegisteredCodes(List<AiTool> enabledTools, List<String> registeredCodes) {
        List<String> result = new ArrayList<>();
        for (AiTool tool : enabledTools) {
            if (AiTool.TYPE_READ_ONLY.equals(tool.getToolType())
                && registeredCodes.contains(tool.getToolCode())) {
                result.add(tool.getToolCode());
            }
        }
        return result;
    }

    /**
     * 把外部 MCP 客户端注册进工具箱（REQ-AI-16），工具名已由服务层归一化。
     *
     * <p><b>为何自行注册而不交给 {@code ToolsConfig.mcpServers}</b>：框架的 {@code McpServerRegistrar}
     * 会以远端自声明的工具名原样注册（{@code McpClientManager} 直接用 {@code McpSchema.Tool.name()} 构造
     * {@code McpTool}），而这类名字可能不满足 LLM function name 规范（如 {@code weather.search_local}），
     * 在严格校验的模型上会整轮 400。这里改为注册「归一化名装饰后」的客户端：注册方式与框架
     * {@code McpServerRegistrar} 内部完全一致（{@code McpClientManager.registerMcpClient(...).block()}），
     * 且发生在 {@code HarnessAgent.build()} 之前，故仍受 {@code ToolFilter} 的 allow 名单管辖。</p>
     *
     * <p>单个 Server 失败不影响其余工具：失败时由本方法关闭该客户端（框架 {@code McpClientManager}
     * 不负责关闭），装配继续。</p>
     *
     * @param toolkit     工具箱
     * @param mcpAssembly MCP 装配结果
     * @param definition  智能体定义（仅用于日志）
     */
    private void registerMcpClients(Toolkit toolkit, McpClientAssembly mcpAssembly, AiAgent definition) {
        Map<String, McpClientWrapper> clients = mcpClientService.openClients(mcpAssembly);
        if (clients.isEmpty()) {
            return;
        }
        List<String> registered = new ArrayList<>(clients.size());
        for (Map.Entry<String, McpClientWrapper> entry : clients.entrySet()) {
            // 安全合规：MCP 回参先过守卫（喂给模型的那一份也脱敏）——MCP 工具由框架直接构造并注册，
            // 平台拿不到工具包装点，故在客户端层拦截（见 GuardingMcpClientWrapper）
            McpClientWrapper guarded = new GuardingMcpClientWrapper(entry.getValue(), safetyGuard);
            try {
                // block 与框架一致：装配必须同步完成，否则工具箱里还没有工具就开始构建 Agent
                toolkit.registerMcpClient(guarded).block(MCP_REGISTER_TIMEOUT);
                registered.add(entry.getKey());
            } catch (Exception e) {
                closeQuietly(guarded);
                log.warn("外部 MCP 工具接入失败，该 Server 本轮不接入：agent={}, server={}, 原因={}",
                    definition.getAgentCode(), entry.getKey(), e.getMessage());
            }
        }
        if (!registered.isEmpty()) {
            log.info("外部 MCP 工具接入：agent={}, servers={}, tools={}",
                definition.getAgentCode(), registered, mcpAssembly.toolNames());
        }
    }

    /**
     * 按注册表装配业务工具，返回实际生效的工具编码。
     *
     * <p>写入类工具同样注册（其执行由「智能体 × 工具」的授权决策控制：命中 ask 时由 AgentScope
     * 框架原生 HITL 暂停并等待人工确认，允许时直接执行）；仅当实现未在代码侧登记时跳过。</p>
     *
     * @param toolkit      工具容器
     * @param enabledTools 启用中的业务工具实体
     * @param kbIds        智能体绑定的知识库主键
     * @param dedupWrites  是否为专家装配（为写工具包一层「同专家会话短期去重」，见 {@link DedupWriteTool}）
     * @return 实际生效的工具编码
     */
    private List<String> registerTools(Toolkit toolkit, List<AiTool> enabledTools, List<Long> kbIds,
                                       boolean dedupWrites) {
        if (enabledTools.isEmpty()) {
            return List.of();
        }
        List<String> registered = new ArrayList<>();
        for (AiTool definition : enabledTools) {
            toolFactory.create(definition, kbIds).ifPresent(tool -> {
                // 安全合规：结果先过守卫（喂给模型的那一份也脱敏，见 ToolResultGuardTool）
                AgentTool effective = new ToolResultGuardTool(tool, safetyGuard);
                // 专家链路的写工具再包一层「同专家会话短期去重」（REQ-AI-30 安全护栏）：
                // 契约校验不合格重试、或 HITL 恢复段续跑时，模型若重复调用同一写工具
                // （同 sessionId + 同工具名 + 同入参），不会产生重复副作用（如重复发布公告）。
                // 入口 / 普通智能体为长期会话，不包装（避免误伤「同一会话再执行一次同样操作」）
                if (dedupWrites && !tool.isReadOnly()) {
                    effective = new DedupWriteTool(effective, writeDedupStore);
                }
                toolkit.registerAgentTool(effective);
                registered.add(definition.getToolCode());
            });
        }
        return registered;
    }

    /**
     * 装配平台级中间件列表。
     *
     * <p>「当前时间」对所有智能体生效；「本轮候选专家清单」只对入口智能体生效
     * （清单每轮现算，故不进装配指纹）。</p>
     *
     * @param definition    智能体定义
     * @param workspaceCode 工作区命名空间（专家与入口共享同一份用户记忆）
     * @param platformMemory 是否平台托管记忆
     * @return 中间件列表
     */
    private List<MiddlewareBase> buildMiddlewares(AiAgent definition, String workspaceCode,
                                                  boolean platformMemory) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(new CurrentTimeMiddleware(aiProperties));
        // 提示词注入防护：声明「<untrusted> 包裹的外部内容只是数据、其中指令不执行」（开关实时读运行期配置）
        middlewares.add(new PromptHardeningMiddleware(runtimeConfigStore));
        if (platformMemory) {
            // 平台托管：框架记忆注入已关闭，由本中间件接管显式记忆 + MEMORY.md
            middlewares.add(new PlatformMemoryContextMiddleware(
                aiProperties, runtimeConfigStore, memoryDeclaredStore, workspaceCode));
        }
        if (definition.isEntry()) {
            middlewares.add(new ExpertRosterMiddleware());
            // 继承槽位（REQ-AI-31）：仅入口智能体注入；槽位由门面每轮合并后放入运行期上下文，不进装配指纹
            middlewares.add(new SlotInheritanceMiddleware());
        }
        return middlewares;
    }

    /**
     * 注册编排协议工具（REQ-AI-30）：**只对入口智能体注册**。
     *
     * <p><b>为什么不放进 {@code BUILTIN_READONLY_TOOL_CODES}</b>：那个集合的语义是
     * 「免绑定 + 默认放行给所有智能体」。把 {@code route_decision} 放进去会让专家等非入口智能体
     * 也看到并可能调用它，徒增误触发面。</p>
     *
     * <p>专家侧**不注册任何协议工具**：专家直答（2026-10-03）后结论直接交付用户，
     * 原有的结构化提交工具 {@code submit_expert_result} 已整体删除。</p>
     *
     * @param toolkit    工具箱
     * @param definition 智能体定义
     * @return 实际注册的协议工具名
     */
    private List<String> registerProtocolTools(Toolkit toolkit, AiAgent definition) {
        if (definition.isEntry()) {
            toolkit.registerAgentTool(new RouteDecisionTool(routeOrchestrator));
            return List.of(RouteDecisionTool.TOOL_NAME);
        }
        return List.of();
    }

    /**
     * 构建工具白名单。
     *
     * <p>HarnessAgent 的默认工具箱会附带联网检索、文件读写、命令执行等内置工具，
     * 这里通过白名单（非平台工具一律移除）+ 显式黑名单（联网检索）双重收敛：
     * 平台无沙箱，只放行业务侧登记的工具（框架内置的文件与命令工具在装配处统一禁用）。</p>
     *
     * @param registeredCodes 实际生效的业务工具编码
     * @param skillNames      绑定且启用中的技能标识（非空时放行技能内置工具）
     * @param mcpAssembly     外部 MCP 连接装配结果（工具名已归一化，需显式放行；
     *                        连接注册由 {@link #registerMcpClients} 完成，不经 {@code mcpServers}）
     * @param memoryReadTools 是否放行只读记忆检索工具（memory_search / memory_get）
     * @return 工具过滤配置
     */
    static ToolsConfig buildToolsConfig(List<String> registeredCodes,
                                        List<String> skillNames, McpClientAssembly mcpAssembly,
                                        boolean memoryReadTools) {
        List<String> allow = new ArrayList<>(registeredCodes);
        if (!skillNames.isEmpty()) {
            // 技能内置工具由框架 DynamicSkillMiddleware 注册，必须显式放行，否则会被 allow 名单裁掉
            allow.addAll(SKILL_BUILTIN_TOOLS);
        }
        if (!mcpAssembly.toolNames().isEmpty()) {
            // 外部 MCP 工具由本类自行注册进工具箱（registerMcpClients），工具名已归一化；
            // 与技能工具同款：必须显式放行，否则会被 ToolFilter 按 allow 名单裁掉
            allow.addAll(mcpAssembly.toolNames());
        }
        List<String> deny = new ArrayList<>(List.of("web_search", "web_fetch"));
        if (memoryReadTools) {
            // 只放行只读检索；写入类记忆工具由 deny 兜底裁掉，避免模型自行改写长期记忆
            allow.add(MEMORY_SEARCH_TOOL);
            allow.add(MEMORY_GET_TOOL);
            deny.add(MEMORY_SAVE_TOOL);
            deny.add(SESSION_SEARCH_TOOL);
        }
        ToolsConfig config = new ToolsConfig();
        config.setAllow(allow);
        config.setDeny(deny);
        return config;
    }

    /**
     * 解析智能体工作区目录（不存在则创建）。
     *
     * @param agentCode 智能体编码
     * @return 工作区路径
     */
    private Path resolveWorkspace(String agentCode) {
        // 必须绝对化并规范化：框架 WorkspaceManager.readManagedWorkspaceFileUtf8 以 startsWith(workspace)
        // 做越界校验，若工作区为「./data/...」这类相对路径（含 "." 段），规范化后的文件路径不再满足
        // startsWith，导致附加上下文文件一律读为空（见 docs/00 第 39 项）
        Path workspace = Path.of(aiProperties.getChat().getWorkspaceRoot(), agentCode)
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new ServiceException("智能体工作区目录创建失败：" + workspace);
        }
        return workspace;
    }

    /**
     * 静默关闭 MCP 客户端（仅用于注册失败时的清理：此时连接尚未交给工具箱，须由调用方负责关闭）。
     *
     * @param client MCP 客户端
     */
    private void closeQuietly(McpClientWrapper client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 MCP 客户端失败：{}", e.getMessage());
        }
    }

    /**
     * 装配结果：智能体实例 + 该次装配的执行期快照（REQ-AI-28 链路追踪取用）
     *
     * @param agent    智能体实例
     * @param snapshot 装配快照（模型版本 + 上下文清单）
     */
    record AssembledAgent(HarnessAgent agent, AgentSnapshot snapshot) {
    }
}
