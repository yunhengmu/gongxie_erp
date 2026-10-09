package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.model.Model;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.tools.GetCurrentDeptTool;
import org.dromara.ai.core.agent.tools.GetCurrentPermissionsTool;
import org.dromara.ai.core.agent.tools.GetCurrentTimeTool;
import org.dromara.ai.core.agent.tools.GetCurrentUserTool;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentKb;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体装配注册表：按定义装配并缓存 {@link HarnessAgent}。
 *
 * <p>定义（模型 / 提示词 / 工具 / 知识库 / 状态）发生变化时指纹随之变化，下次取用时自动销毁旧实例并重建，
 * 从而保证「定义修改后立即生效」，同时避免每轮对话都重建模型 HTTP 客户端。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AgentRegistry {

    /**
     * 服务地址的对话端点后缀（配置若已带该后缀则去除，交给模型客户端自行拼接）
     *
     * <p>供 {@link AgentModelFactory} 引用（同包可见）。</p>
     */
    static final String CHAT_ENDPOINT_SUFFIX = "/chat/completions";

    /**
     * 权限规则的来源标识：来自智能体 × 工具的显式决策
     */
    public static final String SOURCE_AGENT_TOOL = "agent_tool";

    /**
     * 权限规则的来源标识：来自平台内置无害只读工具的默认放行
     */
    public static final String SOURCE_BUILTIN = "builtin";

    /**
     * 权限规则的来源标识：来自子智能体编排工具的放行（REQ-AI-04）
     */
    public static final String SOURCE_SUBAGENT = "subagent";

    /**
     * 权限规则的来源标识：来自会话所选「场景包」内的工具授权决策
     */
    public static final String SOURCE_PACK = "scenario_pack";

    /**
     * 权限规则的来源标识：来自**会话级资源选择器**直接选中的工具（REQ-AI-32）
     *
     * <p>优先级最高（越显式越优先）：直接选中的工具档位取选中时的默认（只读 → 允许 / 写入 → 需确认），
     * 覆盖包内决策与智能体绑定决策。</p>
     */
    public static final String SOURCE_SESSION = "session";

    /**
     * 权限规则的来源标识：来自编排协议工具（{@code route_decision}）的放行
     *
     * <p>它是链路内部协议而非业务能力，故不提供授权配置入口（见 docs/16 §6.10）。</p>
     */
    public static final String SOURCE_ROUTE = "route";

    /**
     * 专家执行装配的缓存键后缀（REQ-AI-30）。
     *
     * <p>专家走**独立装配**（只读业务工具 + 平台内置只读工具，子智能体能力关闭），
     * 与「用户直接与该专家对话」的装配实例区分开，互不影响：
     * 后者仍完全按智能体定义生效（写工具、子智能体照旧）。</p>
     *
     * <p>缓存规模有界——每个专家多一个常驻实例，而不是按会话或按任务膨胀。</p>
     */
    static final String EXPERT_CACHE_SUFFIX = "__expert__";

    /**
     * 子智能体编排与后台任务类**平台工具**（框架注册，不属于业务工具注册表）。
     *
     * <p>绑定子智能体时对这些工具追加 ALLOW 规则：它们只做「委派 / 收发任务 / 取结果」，
     * 本身不产生业务副作用——真正的副作用工具留在主智能体与子智能体的业务工具侧，
     * 且子智能体只能拿到只读工具。若不放行，委派动作会落入全局默认（未命中即 ASK），
     * 每委派一次都要人工确认，子智能体能力实际不可用（已实测：agent_spawn 触发 HITL 暂停）。</p>
     */
    private static final List<String> SUBAGENT_COORDINATION_TOOLS = List.of(
        "agent_spawn", "agent_send", "agent_list",
        "task_output", "task_cancel", "task_list", "wait_async_results");

    /**
     * 强制同步的子智能体调用超时（秒），覆盖模型自行给出的 timeout_seconds。
     *
     * <p>本平台是「一问一答」的流式会话，同步等待必须有上限，否则一次委派可能拖垮整个回答；
     * 超时后框架返回 {@code status: timeout} 并中断子智能体（不会 promote 成后台任务）。</p>
     */
    public static final int SUBAGENT_SYNC_TIMEOUT_SECONDS = 120;

    /**
     * 平台内置的**无害只读工具**编码（与 {@code ai_tool.tool_code} 一致）：智能体**无需绑定**
     * 即会注册进工具箱，并在未绑定时默认获得 ALLOW 权限，从而不必逐个智能体配置。
     *
     * <p><b>准入条件（加入前必须逐条确认）</b>：① 只读、无任何副作用；② 不依赖提问用户身份
     * 做数据过滤（无越权面）；③ 参数无副作用面（如不接受任意路径 / 命令 / URL）。
     * 因此**禁止**把知识库检索（需按用户过滤）、写工具（需人工确认）或框架内置的文件 / 命令工具放进本集合。</p>
     *
     * <p>若某智能体在定义页**显式绑定**了这里的工具，则完全按其绑定决策处理（「需确认 / 禁止」
     * 依然生效），本默认放行不会覆盖显式意图。</p>
     */
    public static final Set<String> BUILTIN_READONLY_TOOL_CODES = Set.of(
        GetCurrentTimeTool.TOOL_NAME,
        GetCurrentUserTool.TOOL_NAME,
        GetCurrentDeptTool.TOOL_NAME,
        GetCurrentPermissionsTool.TOOL_NAME);

    /**
     * 技能内置工具名（由框架 DynamicSkillMiddleware 注册，需显式放进 ToolsConfig 白名单，
     * 否则会被 allow 名单裁掉）
     */
    private static final List<String> SKILL_BUILTIN_TOOLS = List.of("load_skill_through_path", "read_skill");

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

    /**
     * 子智能体模型标识的前缀（{@code SubagentDeclaration.model} 只接受字符串，
     * 由装配期注入的 {@code modelResolver} 还原为模型实例）
     *
     * <p>供 {@link SubagentAssembler} 与 {@link AgentModelFactory} 引用（同包可见）。</p>
     */
    static final String SUBAGENT_MODEL_TOKEN_PREFIX = "bizbuddy-model:";

    /**
     * 子智能体「不下放任何业务工具」时使用的占位工具名。
     *
     * <p>框架 {@code HarnessAgentBuilderSupport.allowlistedInheritedToolkit} 的语义是
     * **「白名单为空＝不过滤，继承父级全部工具」**，因此「零下放」必须给出一个不可能命中的名字，
     * 否则父级的写入类工具会被子智能体原样继承并绕过人工确认（子智能体不继承父级 allow / ask 规则）。</p>
     *
     * <p>供 {@link SubagentAssembler} 引用（同包可见）。</p>
     */
    static final String SUBAGENT_TOOL_NONE = "__no_subagent_tool__";

    /**
     * 装配缓存：键为「智能体主键 × 资源集签名」的复合键（REQ-AI-32；专家再带 {@code __expert__} 段），
     * 值为该次装配的实例 + 指纹 + 快照。
     *
     * <p>资源集身份（选中了哪些场景包 / 工具 / 技能 / MCP 工具）压成确定性签名计入键；
     * 资源**内容**变更（包内容、工具启停、决策等）走装配指纹触发重建。</p>
     */
    private final ConcurrentHashMap<String, AgentHolder> cache = new ConcurrentHashMap<>();

    /**
     * 会话 → 该会话最近一次装配所用的资源集签名（REQ-AI-32，键为 {@code agentId#sessionId}）。
     *
     * <p>用于在会话内**切换资源组合**时丢弃「目标实例」：框架 {@code AgentState} 走版本化 CAS
     * （{@code saveIfVersion → getVersioned}），当某实例先服务过本会话、随后会话状态被另一实例推进时，
     * 再复用该实例会反序列化失败（dev 实测：{@code Failed to get versioned state: agent_state}）。
     * 丢弃后新建即可规避；仅在切换时发生一次性重建。</p>
     */
    private final ConcurrentHashMap<String, String> sessionSignature = new ConcurrentHashMap<>();

    private final AiAgentToolMapper agentToolMapper;

    private final AiAgentKbMapper agentKbMapper;

    private final IAiToolService toolService;

    private final IAiSkillService skillService;

    private final IAiSubagentService subagentService;

    private final AiToolFactory toolFactory;

    private final AgentStateStore stateStore;

    /**
     * 技能仓库：本项目数据库来源（{@code public.ai_skill} / {@code ai_skill_resource}，只读）。
     *
     * <p>技能来源已收口为单一来源——平台只支持在本项目数据库维护技能，不再支持「项目全局目录」，
     * 故本地直接持有仓库 Bean，不再经过来源配置的中转（见 {@code AgentScopeConfig#aiSkillRepository}）。</p>
     */
    private final AgentSkillRepository aiSkillRepository;

    private final AiProperties aiProperties;

    /**
     * 专家写工具短期去重（REQ-AI-30 安全护栏）：只包装专家装配的写工具
     */
    private final AiWriteDedupStore writeDedupStore;

    private final AiPermissionModeStore permissionModeStore;

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 外部 MCP 连接（REQ-AI-16）：平台级配置，**按会话所选场景包**接入其工具
     */
    private final IAiMcpClientService mcpClientService;

    /**
     * 会话级资源集（REQ-AI-32）：把「场景包 + 直接选中的工具 / 技能 / MCP 工具」展开为装配输入，
     * 装配单元为「智能体 × 资源集签名」
     */
    private final IAiSessionResourceService sessionResourceService;

    /**
     * 模型构造工厂：按配置构造对话模型与生成参数、解析各可选模型槽位
     */
    private final AgentModelFactory modelFactory;

    /**
     * 装配指纹计算器：定义与各运行期策略 → 指纹字符串
     */
    private final AgentFingerprintCalculator fingerprintCalculator;

    /**
     * 装配快照工厂：构造执行期装配快照（REQ-AI-28 链路追踪）
     */
    private final AgentSnapshotFactory snapshotFactory;

    /**
     * 子智能体装配支撑：生成声明并解析工具白名单（REQ-AI-04）
     */
    private final SubagentAssembler subagentAssembler;

    /**
     * 专家编排器（REQ-AI-30）：仅供构造 {@link RouteDecisionTool} 使用。
     *
     * <p>用 {@code @Lazy} 注入以打破「注册表 ↔ 编排器」的构造期循环依赖：
     * 编排器装配专家要调 {@link #getExpert}，而注册表构造路由工具要拿编排器。</p>
     */
    private final AiRouteOrchestrator routeOrchestrator;

    /**
     * @param agentToolMapper    智能体工具关联 Mapper
     * @param agentKbMapper      智能体知识库关联 Mapper
     * @param toolService        业务工具注册 Service
     * @param skillService       技能仓库 Service（绑定关系与启用状态）
     * @param subagentService    子智能体 Service（绑定关系与启用状态，REQ-AI-04）
     * @param toolFactory        业务工具工厂（implName → AgentTool）
     * @param stateStore         AgentScope 状态存储
     * @param aiSkillRepository 技能仓库（本项目数据库，平台唯一技能来源）
     * @param aiProperties       平台配置
     * @param permissionModeStore 全局权限模式
     * @param runtimeConfigStore  运行期策略配置（压缩 / 计划模式）
     * @param mcpClientService    外部 MCP 连接（REQ-AI-16）
     * @param sessionResourceService 会话级资源集（REQ-AI-32）
     * @param modelFactory        模型构造工厂
     * @param fingerprintCalculator 装配指纹计算器
     * @param snapshotFactory     装配快照工厂
     * @param subagentAssembler   子智能体装配支撑
     * @param routeOrchestrator   专家编排器（REQ-AI-30，延迟注入以打破循环依赖）
     */
    public AgentRegistry(AiAgentToolMapper agentToolMapper,
                         AiAgentKbMapper agentKbMapper,
                         IAiToolService toolService,
                         IAiSkillService skillService,
                         IAiSubagentService subagentService,
                         AiToolFactory toolFactory,
                         @Qualifier("aiAgentStateStore") AgentStateStore stateStore,
                         AgentSkillRepository aiSkillRepository,
                         AiProperties aiProperties,
                         AiWriteDedupStore writeDedupStore,
                         AiPermissionModeStore permissionModeStore,
                         AiRuntimeConfigStore runtimeConfigStore,
                         IAiMcpClientService mcpClientService,
                         IAiSessionResourceService sessionResourceService,
                         AgentModelFactory modelFactory,
                         AgentFingerprintCalculator fingerprintCalculator,
                         AgentSnapshotFactory snapshotFactory,
                         SubagentAssembler subagentAssembler,
                         @Lazy AiRouteOrchestrator routeOrchestrator) {
        this.agentToolMapper = agentToolMapper;
        this.agentKbMapper = agentKbMapper;
        this.toolService = toolService;
        this.skillService = skillService;
        this.subagentService = subagentService;
        this.toolFactory = toolFactory;
        this.stateStore = stateStore;
        this.aiSkillRepository = aiSkillRepository;
        this.aiProperties = aiProperties;
        this.writeDedupStore = writeDedupStore;
        this.permissionModeStore = permissionModeStore;
        this.runtimeConfigStore = runtimeConfigStore;
        this.mcpClientService = mcpClientService;
        this.sessionResourceService = sessionResourceService;
        this.modelFactory = modelFactory;
        this.fingerprintCalculator = fingerprintCalculator;
        this.snapshotFactory = snapshotFactory;
        this.subagentAssembler = subagentAssembler;
        this.routeOrchestrator = routeOrchestrator;
    }

    /**
     * 取用（必要时装配）智能体实例（无会话资源集）。
     *
     * @param definition 智能体定义
     * @return HarnessAgent 实例
     */
    public HarnessAgent get(AiAgent definition) {
        return get(definition, AiSessionResourceSet.empty());
    }

    /**
     * 取用（必要时装配）「智能体 × 单个场景包」实例（兼容路径：无人值守链路 / 旧调用）。
     *
     * @param definition 智能体定义
     * @param pack       会话所选场景包（可为 null：表示不选包）
     * @return HarnessAgent 实例
     */
    public HarnessAgent get(AiAgent definition, AiScenarioPack pack) {
        return get(definition, sessionResourceService.ofSinglePack(pack));
    }

    /**
     * 取用（必要时装配）「智能体 × 会话资源集」实例（REQ-AI-32）。
     *
     * <p>装配单元自 2026-10-02 起为「智能体 × 资源集签名」：资源集内的业务工具与技能**叠加**到智能体
     * 既有绑定之上（同一工具以**直接选中决策优先**，其次包内决策），外部 MCP 工具**仅按资源集接入**。
     * 缓存键与装配指纹都纳入资源集，故改选择后下一轮对话即重新装配生效。</p>
     *
     * @param definition 智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return HarnessAgent 实例
     */
    public HarnessAgent get(AiAgent definition, AiSessionResourceSet resources) {
        return getInternal(definition, resources, false);
    }

    /**
     * 取用（必要时装配）**专家执行**专用实例（REQ-AI-30，无会话资源集）。
     *
     * @param definition 专家智能体定义
     * @return HarnessAgent 实例
     */
    public HarnessAgent getExpert(AiAgent definition) {
        return getExpert(definition, AiSessionResourceSet.empty());
    }

    /**
     * 取用（必要时装配）**专家执行**专用实例（继承会话资源集，REQ-AI-32）。
     *
     * <p>专家的工具可见面 = 只读业务工具 ∪ 平台内置无害只读工具
     * ∪ **会话资源集中的只读工具**（口径放宽）；专家**继承会话资源集**（不再 {@code pack=null}）。
     * 子智能体能力关闭（专家是纯分析型，见 docs/16 §6.7）。
     * 它与「用户直接与该专家对话」的装配实例
     * **分开缓存**（键后缀 {@link EXPERT_CACHE_SUFFIX}），因此后者仍完全按智能体定义生效。</p>
     *
     * <p><b>写工具（REQ-AI-30 Phase 2）</b>：仅当该专家开启 {@code expert_write_enabled='1'} 时才把
     * 写工具纳入可见面，且**只有「允许」档会被执行**（「需确认」档在运行期被改写为拒绝，
     * 「禁止」档不注册）；未开启（默认）时专家强制只读。</p>
     *
     * @param definition 专家智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return HarnessAgent 实例
     */
    public HarnessAgent getExpert(AiAgent definition, AiSessionResourceSet resources) {
        return getInternal(definition, resources, true);
    }

    /**
     * 取用（必要时装配）实例的内部实现。
     *
     * @param definition 智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @param expertMode 是否专家执行装配（只读强制 + 协议工具，子智能体关闭）
     * @return HarnessAgent 实例
     */
    private HarnessAgent getInternal(AiAgent definition, AiSessionResourceSet resources, boolean expertMode) {
        if (definition == null || definition.getId() == null) {
            throw new ServiceException("智能体定义不存在");
        }
        AiSessionResourceSet res = resources == null ? AiSessionResourceSet.empty() : resources;
        List<AiAgentTool> bindings = queryBindings(definition.getId());
        // 合并决策「智能体绑定 → 包内 → 直接选中（越显式越优先）」后，被禁止的工具不注册进 Toolkit（模型不可见）
        Map<String, String> merged = mergeToolDecisions(bindings, res.mergedToolDecisions());
        List<String> allowedCodes = merged.entrySet().stream()
            .filter(entry -> !AiAgentTool.DECISION_DENY.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        // 平台内置无害只读工具（REQ 无编号，见 BUILTIN_READONLY_TOOL_CODES）：未绑定时也一并装配，
        // 避免每个智能体都要单独绑定；已绑定的不并入，完全按绑定 / 包内 / 直接选中决策处理
        Set<String> unionCodes = new LinkedHashSet<>(allowedCodes);
        for (String code : BUILTIN_READONLY_TOOL_CODES) {
            if (!merged.containsKey(code)) {
                unionCodes.add(code);
            }
        }
        // 工具启用状态与定义本身分属两张表，一并纳入指纹，停用/改实现/改决策后同样触发重建
        List<AiTool> matchedTools = toolService.queryEnabledByCodes(unionCodes);
        // 专家写工具开关（REQ-AI-30 Phase 2）：'1' 时专家按「智能体 × 工具」三档授权生效——
        // 只有「允许（decision='0'）」档的写工具会被放行（'1' 需确认档在运行期被改写为拒绝、
        // '2' 禁止档已在上游 merged 中排除）。开关未开（默认 '0'）时保持 Phase 1 行为：
        // 与只读白名单求交，写工具**不注册**（模型不可见，而非"注册后拒绝"）；
        // 平台内置只读工具本身也是只读，故一并保留。
        // 用三目一次性赋值：变量需「有效 final」，否则装配缓存 lambda 捕获不到。
        boolean expertWriteAllowed = expertMode && definition.expertWriteAllowed();
        List<AiTool> enabledTools = expertMode && !expertWriteAllowed
            ? matchedTools.stream().filter(tool -> AiTool.TYPE_READ_ONLY.equals(tool.getToolType())).toList()
            : matchedTools;
        List<Long> kbIds = queryKbIds(definition.getId());
        // 技能（REQ-AI-10）：智能体绑定技能 ∪ 资源集技能（包内 ∪ 直接选中）；是否启用会影响装配，故纳入指纹
        List<String> skillNames = skillService.resolveAgentSkillFilterKeys(definition.getId(), res.mergedSkillIds());
        // 子智能体（REQ-AI-04）：绑定的子智能体「启用中」才参与装配（生成 SubagentDeclaration），故纳入指纹
        // 专家执行链路不注册子智能体能力：专家是纯分析型，避免"专家再委派"这类越界面与审计盲区
        List<AiSubagent> subagents = expertMode
            ? List.of() : subagentService.queryBoundEnabledSubagents(definition.getId());
        // 运行期策略（压缩 / 计划模式 / 长期记忆 / 工作区上下文）属于装配参数，纳入指纹以便保存后自动重建
        AiCompactionConfigBo compactionPolicy = runtimeConfigStore.compaction();
        AiPlanConfigBo planPolicy = runtimeConfigStore.plan();
        AiMemoryConfigBo memoryPolicy = runtimeConfigStore.memory();
        AiWorkspaceConfigBo workspacePolicy = runtimeConfigStore.workspace();
        // 外部 MCP 连接（REQ-AI-16 / REQ-AI-32）：仅接入**资源集内**（包内 ∪ 直接选中）的 MCP 工具；无资源时为空
        McpClientAssembly mcpAssembly = mcpClientService.resolveForAssembly(res.mergedMcpSelections());
        // 模型配置（地址 / 密钥 / 超时 / 重试 / 启用状态）也是装配参数：改配置后须让已装配实例失效重建
        String modelSignature = fingerprintCalculator.modelSignature(definition);
        String fingerprint = fingerprintCalculator.buildFingerprint(definition, enabledTools, bindings, modelSignature,
            compactionPolicy, planPolicy, memoryPolicy, workspacePolicy, skillNames,
            mcpAssembly, subagents, res.packSignature())
            // 专家执行装配与普通装配是两套实例：标记并入指纹，角色或模式变化即触发重建
            + "|expert=" + expertMode;
        String cacheKey = cacheKey(definition.getId(), expertMode, res.signature());
        // 会话内切换资源组合：丢弃**目标实例**（它可能此前已服务过本会话，复用会触发框架状态 CAS 读取失败）
        if (!expertMode && res.sessionId() != null) {
            String sessionKey = definition.getId() + "#" + res.sessionId();
            String previous = sessionSignature.put(sessionKey, res.signature());
            if (previous != null && !previous.equals(res.signature())) {
                AgentHolder stale = cache.remove(cacheKey);
                if (stale != null) {
                    closeQuietly(stale.agent());
                    log.info("会话资源集变更，丢弃目标装配实例：agent={}, session={}, sig={}",
                        definition.getAgentCode(), res.sessionId(), res.signature());
                }
            }
        }
        AgentHolder holder = cache.compute(cacheKey, (key, exists) -> {
            if (exists != null && exists.fingerprint().equals(fingerprint)) {
                return exists;
            }
            if (exists != null) {
                closeQuietly(exists.agent());
            }
            AssembledAgent assembled = assemble(definition, enabledTools, kbIds, skillNames, bindings,
                res, subagents, compactionPolicy, planPolicy, memoryPolicy,
                workspacePolicy, mcpAssembly, expertMode);
            return new AgentHolder(definition.getId(), assembled.agent(), fingerprint, assembled.snapshot());
        });
        return holder.agent();
    }

    /**
     * 构建指定「智能体 × 会话资源集」的「管理端档位」权限上下文（不含任何用户级信任决策）。
     *
     * <p>装配实例是**智能体级、跨用户共享**的，因此不能在装配期把某个用户的「记住该工具」信任
     * 写进共享实例；对话链路改为每轮按提问用户把信任并入该会话（`(userId, sessionId)` 槽位）的
     * 权限上下文，本方法即用于取那份合并前的基线（工具档位 + 包内档位 + 直接选中档位 + 内置只读 + 子智能体协调工具）。</p>
     *
     * @param definition 智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return 管理端档位权限上下文
     */
    public PermissionContextState permissionContextOf(AiAgent definition, AiSessionResourceSet resources) {
        if (definition == null || definition.getId() == null) {
            throw new ServiceException("智能体定义不存在");
        }
        AiSessionResourceSet res = resources == null ? AiSessionResourceSet.empty() : resources;
        List<AiAgentTool> bindings = queryBindings(definition.getId());
        Map<String, String> mcpDecisions = mcpClientService.resolveForAssembly(res.mergedMcpSelections())
            .toolDecisions();
        List<AiSubagent> subagents = subagentService.queryBoundEnabledSubagents(definition.getId());
        return buildPermissionContext(bindings, res.packToolDecisions(), res.directToolDecisions(), mcpDecisions,
            !subagents.isEmpty(), definition.isEntry());
    }

    /**
     * 构建指定「智能体 × 单个场景包」的「管理端档位」权限上下文（兼容路径）。
     *
     * @param definition 智能体定义
     * @param pack       会话所选场景包（可为 null）
     * @return 管理端档位权限上下文
     */
    public PermissionContextState permissionContextOf(AiAgent definition, AiScenarioPack pack) {
        return permissionContextOf(definition, sessionResourceService.ofSinglePack(pack));
    }

    /**
     * 构建指定智能体**专家执行装配**的「管理端档位」权限上下文（不含任何用户级信任决策）。
     *
     * <p>与 {@link #permissionContextOf(AiAgent, AiSessionResourceSet)} 的区别：这里按「专家模式」构建
     * （子智能体协调工具不放行、编排协议工具 {@code route_decision} 不放行），与
     * {@link #getExpert(AiAgent, AiSessionResourceSet)} 实际装配出的实例严格一致；
     * 资源集（REQ-AI-32）与常规链路同源，故专家侧对会话选中工具的档位判定与装配一致。</p>
     *
     * <p><b>为什么必须单独提供</b>：编排链路要把用户级「记住该工具」信任并入
     * {@code (userId, routeRunId#expertAgentId)} 槽位，而合并是「以管理端档位为基线重建上下文」。
     * 基线必须与专家装配实例的规则集合一致（专家装配关闭子智能体），否则重建后会出现
     * 「实例已注册该工具、而重建出的规则未放行」的错配，专家续跑段调用会被框架拒绝。</p>
     *
     * @param definition 专家智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return 专家执行装配的管理端档位权限上下文
     */
    public PermissionContextState permissionContextOfExpert(AiAgent definition, AiSessionResourceSet resources) {
        if (definition == null || definition.getId() == null) {
            throw new ServiceException("智能体定义不存在");
        }
        AiSessionResourceSet res = resources == null ? AiSessionResourceSet.empty() : resources;
        Map<String, String> mcpDecisions = mcpClientService.resolveForAssembly(res.mergedMcpSelections())
            .toolDecisions();
        return buildPermissionContext(queryBindings(definition.getId()), res.packToolDecisions(),
            res.directToolDecisions(), mcpDecisions,
            false, false);
    }

    /**
     * 构建指定智能体**专家执行装配**的「管理端档位」权限上下文（无会话资源集）。
     *
     * @param definition 专家智能体定义
     * @return 专家执行装配的管理端档位权限上下文
     */
    public PermissionContextState permissionContextOfExpert(AiAgent definition) {
        return permissionContextOfExpert(definition, AiSessionResourceSet.empty());
    }

    /**
     * 取指定「智能体 × 会话资源集」**当前装配结果**的执行期快照（REQ-AI-28 链路追踪取用）。
     *
     * <p>快照在装配时生成并随装配结果缓存，因此它与该次实际使用的模型、工具与策略严格一致；
     * 若尚未装配则先触发一次装配。取不到时返回 null（链路仍会落库，只是快照为空）。</p>
     *
     * @param definition 智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return 装配快照；未装配成功时返回 null
     */
    public AgentSnapshot snapshotOf(AiAgent definition, AiSessionResourceSet resources) {
        if (definition == null || definition.getId() == null) {
            return null;
        }
        AiSessionResourceSet res = resources == null ? AiSessionResourceSet.empty() : resources;
        String key = cacheKey(definition.getId(), false, res.signature());
        // 统一走 get：会话内切换资源组合时的「丢弃目标实例」判定在 get 内完成，
        // 否则这里会取到切换前的旧实例快照（工具清单与实际执行不一致）
        get(definition, res);
        AgentHolder holder = cache.get(key);
        return holder == null ? null : holder.snapshot();
    }

    /**
     * 取指定「智能体 × 单个场景包」**当前装配结果**的执行期快照（兼容路径）。
     *
     * @param definition 智能体定义
     * @param pack       会话所选场景包（可为 null）
     * @return 装配快照；未装配成功时返回 null
     */
    public AgentSnapshot snapshotOf(AiAgent definition, AiScenarioPack pack) {
        return snapshotOf(definition, sessionResourceService.ofSinglePack(pack));
    }

    /**
     * 取指定智能体（无资源集）**当前装配结果**的执行期快照。
     *
     * @param definition 智能体定义
     * @return 装配快照；未装配成功时返回 null
     */
    public AgentSnapshot snapshotOf(AiAgent definition) {
        return snapshotOf(definition, AiSessionResourceSet.empty());
    }

    /**
     * 取指定智能体**专家执行装配**当前结果的执行期快照（REQ-AI-30 链路追踪取用，继承会话资源集）。
     *
     * <p>与 {@link #snapshotOf(AiAgent, AiSessionResourceSet)} 的区别：这里取的是专家模式的独立装配实例
     * （只读、子智能体关闭），因此链路里记录的模型版本与上下文清单与实际执行严格一致。</p>
     *
     * <p>与 {@code snapshotOf} 一样**统一走 {@link #getExpert}**：装配缓存按指纹决定是否重建，
     * 而快照随 holder 一起被替换；只读缓存而不先过一遍装配，就会把**上一次装配**的快照写进链路
     * （实测症状：链路显示 {@code skills=[]} 而实际已装配技能，2026-10-03）。</p>
     *
     * @param definition 专家智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return 装配快照；未装配成功时返回 null
     */
    public AgentSnapshot snapshotOfExpert(AiAgent definition, AiSessionResourceSet resources) {
        if (definition == null || definition.getId() == null) {
            return null;
        }
        AiSessionResourceSet res = resources == null ? AiSessionResourceSet.empty() : resources;
        String key = cacheKey(definition.getId(), true, res.signature());
        // 先过装配：按当前指纹判定是否重建，重建后的快照才与实际执行一致
        getExpert(definition, res);
        AgentHolder holder = cache.get(key);
        return holder == null ? null : holder.snapshot();
    }

    /**
     * 取指定智能体**专家执行装配**当前结果的执行期快照（无会话资源集）。
     *
     * @param definition 专家智能体定义
     * @return 装配快照；未装配成功时返回 null
     */
    public AgentSnapshot snapshotOfExpert(AiAgent definition) {
        return snapshotOfExpert(definition, AiSessionResourceSet.empty());
    }

    /**
     * 清理指定智能体的全部装配缓存（任意场景包维度的实例；定义变更或删除时调用）。
     *
     * @param agentId 智能体主键
     */
    public void evict(Long agentId) {
        if (agentId == null) {
            return;
        }
        cache.entrySet().removeIf(entry -> {
            AgentHolder holder = entry.getValue();
            if (!agentId.equals(holder.agentId())) {
                return false;
            }
            closeQuietly(holder.agent());
            return true;
        });
        // 一并清理该智能体的会话签名记录（REQ-AI-32），避免无界增长
        sessionSignature.keySet().removeIf(key -> key.startsWith(agentId + "#"));
    }

    /**
     * 应用关闭时释放全部智能体实例。
     */
    @PreDestroy
    public void shutdown() {
        cache.values().forEach(holder -> closeQuietly(holder.agent()));
        cache.clear();
    }

    /**
     * 解析记忆抽取与合并所用的模型：优先智能体定义的记忆模型槽位（REQ-AI-06），
     * 未配置或已失效时回退主模型（供手动触发记忆合并使用，与自动合并保持一致）。
     *
     * @param definition 智能体定义
     * @return 记忆模型（始终非空）
     */
    public Model memoryModelFor(AiAgent definition) {
        Model slot = modelFactory.resolveMemoryModel(definition);
        return slot != null ? slot
            : modelFactory.buildChatModel(modelFactory.resolveChatModel(definition.getModelId()));
    }

    /**
     * 装配 HarnessAgent。
     *
     * @param definition   智能体定义
     * @param enabledTools 启用中的业务工具实体（已剔除被禁止的工具；含资源集内叠加的工具）
     * @param kbIds        绑定的知识库主键
     * @param bindings     智能体侧工具绑定（含授权决策）
     * @param resources    会话级资源集（REQ-AI-32；含包内与直接选中的工具 / 技能 / MCP 工具与档位）
     * @param subagents    绑定的启用中子智能体（REQ-AI-04）
     * @param compactionPolicy 上下文压缩策略
     * @param planPolicy   计划模式与任务清单配置
     * @param memoryPolicy 长期记忆配置
     * @param workspacePolicy 工作区上下文配置
     * @param mcpAssembly   外部 MCP 连接装配结果（REQ-AI-16；已按资源集收敛）
     * @param expertMode    是否专家执行装配（子智能体已在外层关闭；专家直答后专家侧不注册任何协议工具）
     * @return HarnessAgent 实例
     */
    private AssembledAgent assemble(AiAgent definition, List<AiTool> enabledTools, List<Long> kbIds,
                                  List<String> skillNames, List<AiAgentTool> bindings,
                                  AiSessionResourceSet resources, List<AiSubagent> subagents,
                                  AiCompactionConfigBo compactionPolicy,
                                  AiPlanConfigBo planPolicy, AiMemoryConfigBo memoryPolicy,
                                  AiWorkspaceConfigBo workspacePolicy,
                                  McpClientAssembly mcpAssembly, boolean expertMode) {
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
            renderDecisions(mergeToolDecisions(bindings, resources.mergedToolDecisions())),
            resources.mergedToolDecisions(), mcpAssembly.toolNames(), resources.signature());
        HarnessAgent.Builder builder = HarnessAgent.builder()
            .name(definition.getAgentCode())
            .description(StringUtils.isBlank(definition.getDescription())
                ? definition.getAgentName() : definition.getDescription())
            .sysPrompt(resolveSysPrompt(definition, skillNames, expertMode))
            .model(model)
            .toolkit(toolkit)
            .stateStore(stateStore)
            .workspace(resolveWorkspace(definition.getAgentCode()))
            .maxIters(maxIters)
            .toolsConfig(buildToolsConfig(registeredCodes, skillNames, mcpAssembly))
            // 工具授权：按「智能体 × 工具」「场景包 × 工具」与「会话直接选中」的合并决策生成 allow / ask / deny 规则
            //（越显式越优先：直接选中 > 包内 > 智能体绑定；写工具走 ask → HITL 确认）
            .permissionContext(buildPermissionContext(bindings, resources.packToolDecisions(),
                resources.directToolDecisions(), mcpAssembly.toolDecisions(),
                !subagents.isEmpty(), definition.isEntry()))
            // 当前时间注入：平台级中间件，所有智能体自动生效；每轮现算，改开关/时区无需重建
            .middlewares(buildMiddlewares(definition));
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
                + "taskList={}, memory={}(maxContextTokens={}, consolidation={} tokens/{} 分钟, retention={}/{} 天, "
                + "flush={}), workspace={}(additionalFiles={})",
            definition.getAgentCode(), compactionPolicy.isEnabled(), compactionPolicy.getTriggerMessages(),
            compactionPolicy.getTriggerTokens(), compactionPolicy.getKeepMessages(),
            compactionPolicy.isFlushBeforeCompact(), compactionPolicy.isOffloadBeforeCompact(),
            compactionPolicy.isToolResultEvictionEnabled(), compactionPolicy.getToolResultEvictionMaxChars(),
            compactionPolicy.getToolResultEvictionPreviewChars(), planPolicy.isEnabled(),
            planPolicy.getPlanFileDirectory(), planPolicy.isTaskListEnabled(),
            memoryPolicy.isEnabled(), memoryPolicy.getMaxContextTokens(), memoryPolicy.getConsolidationMaxTokens(),
            memoryPolicy.getConsolidationMinGapMinutes(), memoryPolicy.getDailyFileRetentionDays(),
            memoryPolicy.getSessionRetentionDays(), memoryPolicy.getFlushMode(), workspacePolicy.isEnabled(),
            workspacePolicy.getAdditionalContextFiles());
        log.info("当前时间注入：agent={}, enabled={}, zone={}", definition.getAgentCode(),
            aiProperties.getChat().isCurrentTimeEnabled(), aiProperties.getChat().getCurrentTimeZone());
        HarnessAgent.Builder tail = builder
            // 记忆工具（memory_search / memory_get / memory_save / session_search）不注册，
            // 记忆仅以「MEMORY.md 注入 + 自动抽取 + 自动合并」方式工作，保持工具集最小化
            .disableMemoryTools();
        if (skillNames.isEmpty()) {
            // 未绑定技能且无资源集技能：不注册技能内置工具、不注入技能清单（与既往行为一致）
            tail = tail.disableDynamicSkills();
        } else {
            // 有技能：接入本项目数据库技能仓库（平台只此一个来源，事务见 AgentScopeConfig#aiSkillRepository），
            // 并以白名单仅放行「绑定且启用中」的技能；
            // 提问用户维度的生效范围在运行期由 SkillFilter 覆盖（见 AiChatFacade）
            tail = tail.skillRepositories(List.of(aiSkillRepository))
                .skillFilter(SkillFilter.only(skillNames.toArray(new String[0])));
        }
        // 技能一律来自本项目数据库：关闭框架默认的工作区（文件系统）技能来源
        tail = tail.disableDefaultWorkspaceSkills();
        // 平台无沙箱：智能体只能调用登记在册的业务工具，禁用内置文件、命令与 Web 工具。
        // 内置 Web 工具（web_fetch / web_search）默认注册并可直接出网，绕过「无裸 HTTP、工具最小化、
        // 全链路审计」的平台纪律，故一并关闭；确需联网能力时应改造为受控工具后再登记。
        tail = tail.disableFilesystemTools().disableShellTool().disableWebTools();
        if (!memoryPolicy.isEnabled()) {
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
    private String resolveSysPrompt(AiAgent definition, List<String> skillNames, boolean expertMode) {
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
    private MemoryConfig.FlushTrigger resolveFlushTrigger(AiMemoryConfigBo policy) {
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
    private List<String> readOnlyRegisteredCodes(List<AiTool> enabledTools, List<String> registeredCodes) {
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
            try {
                // block 与框架一致：装配必须同步完成，否则工具箱里还没有工具就开始构建 Agent
                toolkit.registerMcpClient(entry.getValue()).block(MCP_REGISTER_TIMEOUT);
                registered.add(entry.getKey());
            } catch (Exception e) {
                closeQuietly(entry.getValue());
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
                // 专家链路的写工具包一层「同专家会话短期去重」（REQ-AI-30 安全护栏）：
                // 契约校验不合格重试、或 HITL 恢复段续跑时，模型若重复调用同一写工具
                // （同 sessionId + 同工具名 + 同入参），不会产生重复副作用（如重复发布公告）。
                // 入口 / 普通智能体为长期会话，不包装（避免误伤「同一会话再执行一次同样操作」）
                AgentTool effective = dedupWrites && !tool.isReadOnly()
                    ? new DedupWriteTool(tool, writeDedupStore) : tool;
                toolkit.registerAgentTool(effective);
                registered.add(definition.getToolCode());
            });
        }
        return registered;
    }

    /**
     * 构建工具权限上下文：显式决策翻译为 allow / ask / deny 规则，未命中时按全局模式兜底。
     *
     * <p>决策来源有四处（REQ-AI-32 新增第 ④）：① 智能体 × 工具（{@code ai_agent_tool.decision}）；
     * ② 场景包 × 业务工具（{@code ai_scenario_pack_tool.decision}）；③ 场景包 × MCP 工具
     * （{@code ai_scenario_pack_mcp_tool.decision}，键为**归一化后**的工具名）；④ **会话级资源选择器**
     * 直接选中的工具（来源 {@code session}，档位取选中时的默认：只读 → 允许 / 写入 → 需确认）。
     * 优先级**越显式越优先**：直接选中 &gt; 包内 &gt; 智能体绑定。</p>
     *
     * <p><b>此处刻意不含「记住该工具」的用户级信任决策</b>：装配实例是智能体级、跨用户共享的，
     * 若把某个用户的信任写进共享上下文会让其他用户也被免确认；用户级信任由对话链路每轮按提问用户
     * 并入该 {@code (userId, sessionId)} 槽位的权限上下文（见 {@code AiChatFacade#applyUserTrust}）。</p>
     *
     * @param bindings      智能体侧工具绑定（含授权决策）
     * @param packToolDecisions 场景包内业务工具的授权决策（可为空；多包时后者覆盖前者）
     * @param sessionToolDecisions 会话直接选中的业务工具决策（可为空；覆盖绑定与包内）
     * @param mcpDecisions  资源集内 MCP 工具的授权决策（归一化名 → 决策，可为空）
     * @param subagentsEnabled 是否绑定了启用中的子智能体（决定是否放行编排类平台工具）
     * @param entryAgent    是否入口智能体（决定是否放行 {@code route_decision}）
     * @return 权限上下文
     */
    private PermissionContextState buildPermissionContext(List<AiAgentTool> bindings,
                                                          Map<String, String> packToolDecisions,
                                                          Map<String, String> sessionToolDecisions,
                                                          Map<String, String> mcpDecisions,
                                                          boolean subagentsEnabled,
                                                          boolean entryAgent) {
        Map<String, String> sessionDecisions = sessionToolDecisions == null ? Map.of() : sessionToolDecisions;
        PermissionContextState.Builder builder = PermissionContextState.builder()
            .mode(permissionModeStore.resolve());
        for (AiAgentTool bind : bindings) {
            // 同一工具被包内或会话直接选中显式覆盖时，以更显式者为准（不再生成智能体侧规则）
            if (packToolDecisions.containsKey(bind.getToolCode())
                || sessionDecisions.containsKey(bind.getToolCode())) {
                continue;
            }
            addRule(builder, bind.getToolCode(), bind.getDecision(), SOURCE_AGENT_TOOL);
        }
        for (Map.Entry<String, String> entry : packToolDecisions.entrySet()) {
            // 直接选中优先于包内
            if (sessionDecisions.containsKey(entry.getKey())) {
                continue;
            }
            addRule(builder, entry.getKey(), entry.getValue(), SOURCE_PACK);
        }
        for (Map.Entry<String, String> entry : sessionDecisions.entrySet()) {
            // 会话级资源选择器：直接选中，优先级最高
            addRule(builder, entry.getKey(), entry.getValue(), SOURCE_SESSION);
        }
        for (Map.Entry<String, String> entry : mcpDecisions.entrySet()) {
            addRule(builder, entry.getKey(), entry.getValue(), SOURCE_PACK);
        }
        for (String code : BUILTIN_READONLY_TOOL_CODES) {
            // 平台内置无害只读工具：未绑定时默认放行，避免落入全局模式（默认 DEFAULT＝未命中即 ASK）
            // 而对「看时间」这类无副作用操作弹确认卡片；已显式绑定的不覆盖其决策（含包内 / 直接选中决策）
            boolean bound = bindings.stream().anyMatch(bind -> code.equals(bind.getToolCode()))
                || packToolDecisions.containsKey(code) || sessionDecisions.containsKey(code)
                || mcpDecisions.containsKey(code);
            if (!bound) {
                builder.addAllowRule(code, new PermissionRule(code, null, PermissionBehavior.ALLOW, SOURCE_BUILTIN));
            }
        }
        if (subagentsEnabled) {
            // 子智能体编排 / 后台任务类平台工具默认放行，否则每次委派都会弹人工确认（REQ-AI-04）
            for (String tool : SUBAGENT_COORDINATION_TOOLS) {
                builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, SOURCE_SUBAGENT));
            }
        }
        // 专家直答修订（2026-10-03）：专家侧不再有 submit_expert_result 协议工具（其回复直接交付用户），
        // 故此处只为入口侧的 route_decision 放行
        if (entryAgent) {
            builder.addAllowRule(RouteDecisionTool.TOOL_NAME, new PermissionRule(
                RouteDecisionTool.TOOL_NAME, null, PermissionBehavior.ALLOW, SOURCE_ROUTE));
        }
        return builder.build();
    }

    /**
     * 装配平台级中间件列表。
     *
     * <p>「当前时间」对所有智能体生效；「本轮候选专家清单」只对入口智能体生效
     * （清单每轮现算，故不进装配指纹）。</p>
     *
     * @param definition 智能体定义
     * @return 中间件列表
     */
    private List<MiddlewareBase> buildMiddlewares(AiAgent definition) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(new CurrentTimeMiddleware(aiProperties));
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
     * 按三档决策追加权限规则（0 允许 / 1 需确认 / 2 禁止；其余值按「允许」兜底）。
     *
     * @param builder  权限上下文构建器
     * @param tool     工具名 / 工具编码
     * @param decision 决策值
     * @param source   规则来源标识
     */
    private void addRule(PermissionContextState.Builder builder, String tool, String decision, String source) {
        if (AiAgentTool.DECISION_ASK.equals(decision)) {
            builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, source));
        } else if (AiAgentTool.DECISION_DENY.equals(decision)) {
            builder.addDenyRule(tool, new PermissionRule(tool, null, PermissionBehavior.DENY, source));
        } else {
            builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, source));
        }
    }

    /**
     * 合并「智能体绑定」与「包内工具」的授权决策：同一工具**以包内决策优先**。
     *
     * @param bindings          智能体侧工具绑定
     * @param packToolDecisions 包内工具决策（工具编码 → 决策）
     * @return 合并后的决策（保持「智能体在前、包内覆盖」的顺序）
     */
    private Map<String, String> mergeToolDecisions(List<AiAgentTool> bindings,
                                                   Map<String, String> packToolDecisions) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (AiAgentTool bind : bindings) {
            merged.put(bind.getToolCode(), bind.getDecision());
        }
        if (packToolDecisions != null) {
            merged.putAll(packToolDecisions);
        }
        return merged;
    }

    /**
     * 生成装配缓存键（REQ-AI-32）：{@code agentId#R:<sig>} 或 {@code agentId#__expert__#R:<sig>}。
     *
     * <p>{@code sig} 是会话资源集的**身份签名**（排序后的 type:key 列表 → SHA-256 前 8 字节）：
     * 选中哪些场景包 / 工具 / 技能 / MCP 工具决定实例；资源内容变更由装配指纹触发重建。</p>
     *
     * @param agentId   智能体主键
     * @param expertMode 是否专家执行装配
     * @param signature 资源集签名（可空按空串处理）
     * @return 缓存键
     */
    private String cacheKey(Long agentId, boolean expertMode, String signature) {
        String sig = signature == null ? "" : signature;
        return expertMode
            ? agentId + "#" + EXPERT_CACHE_SUFFIX + "#R:" + sig
            : agentId + "#R:" + sig;
    }

    /**
     * 渲染工具决策（仅用于日志）。
     *
     * @param decisions 工具编码 → 决策
     * @return 形如 code=decision 的字符串
     */
    private String renderDecisions(Map<String, String> decisions) {
        StringBuilder sb = new StringBuilder();
        decisions.forEach((code, decision) -> {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(code).append('=').append(decision);
        });
        return sb.toString();
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
     * @return 工具过滤配置
     */
    private ToolsConfig buildToolsConfig(List<String> registeredCodes,
                                        List<String> skillNames, McpClientAssembly mcpAssembly) {
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
        ToolsConfig config = new ToolsConfig();
        config.setAllow(allow);
        config.setDeny(List.of("web_search", "web_fetch"));
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
     * 查询智能体的工具绑定（含授权决策）。
     *
     * @param agentId 智能体主键
     * @return 工具绑定集合
     */
    private List<AiAgentTool> queryBindings(Long agentId) {
        LambdaQueryWrapper<AiAgentTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentTool::getAgentId, agentId);
        lqw.orderByAsc(AiAgentTool::getId);
        return agentToolMapper.selectList(lqw);
    }

    /**
     * 查询智能体绑定的知识库主键。
     *
     * @param agentId 智能体主键
     * @return 知识库主键集合
     */
    private List<Long> queryKbIds(Long agentId) {
        LambdaQueryWrapper<AiAgentKb> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentKb::getAgentId, agentId);
        lqw.orderByAsc(AiAgentKb::getId);
        return agentKbMapper.selectList(lqw).stream().map(AiAgentKb::getKbId).toList();
    }

    /**
     * 静默关闭智能体实例。
     *
     * @param agent 智能体实例
     */
    private void closeQuietly(HarnessAgent agent) {
        try {
            agent.close();
        } catch (Exception e) {
            log.warn("关闭智能体实例失败：{}", e.getMessage());
        }
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
     * 装配缓存项。
     *
     * @param agentId     所属智能体主键（供按智能体整体失效缓存）
     * @param agent       智能体实例
     * @param fingerprint 定义指纹（含场景包签名）
     * @param snapshot    该次装配的执行期快照
     */
    private record AgentHolder(Long agentId, HarnessAgent agent, String fingerprint, AgentSnapshot snapshot) {
    }

    /**
     * 装配结果：智能体实例 + 该次装配的执行期快照（REQ-AI-28 链路追踪取用）
     *
     * @param agent    智能体实例
     * @param snapshot 装配快照（模型版本 + 上下文清单）
     */
    private record AssembledAgent(HarnessAgent agent, AgentSnapshot snapshot) {
    }

}
