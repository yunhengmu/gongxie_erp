package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.tools.ForgetUserPreferenceTool;
import org.dromara.ai.core.agent.tools.GetCurrentDeptTool;
import org.dromara.ai.core.agent.tools.GetCurrentPermissionsTool;
import org.dromara.ai.core.agent.tools.GetCurrentTimeTool;
import org.dromara.ai.core.agent.tools.GetCurrentUserTool;
import org.dromara.ai.core.agent.tools.RememberUserPreferenceTool;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentKb;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiEntryAgent;
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
import org.dromara.ai.mapper.AiEntryAgentMapper;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiSubagentService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
     * 权限规则的来源标识：来自平台内置显式记忆工具的默认放行（平台托管模式专用）
     */
    public static final String SOURCE_MEMORY = "declared_memory";

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
        GetCurrentPermissionsTool.TOOL_NAME,
        // 框架提供的只读记忆检索工具：只被当前用户可见的记忆命中，无副作用；
        // 仅在开启「只读记忆检索」时注册，未注册时允许规则无实际作用。
        // 不放行会落到全局默认（未命中即 ASK），模型每次查记忆都弹确认卡片
        AgentAssembler.MEMORY_SEARCH_TOOL,
        AgentAssembler.MEMORY_GET_TOOL);

    /**
     * 平台内置的**显式记忆工具**：只在平台托管模式（{@code memory-pipeline-mode=platform}）
     * 且只对入口智能体注册，装配期默认放行（用户明确要求记住属低风险写操作，
     * 且只影响其本人的记忆、可在记忆页撤销），避免每次「请记住」都弹人工确认。
     *
     * <p>与 {@link #BUILTIN_READONLY_TOOL_CODES} 分开放置：那个集合的语义是「免绑定 + 默认放行的只读工具」，
     * 显式记忆工具是写工具，混入会破坏该集合的契约。</p>
     */
    public static final Set<String> BUILTIN_MEMORY_TOOL_CODES = Set.of(
        RememberUserPreferenceTool.TOOL_NAME,
        ForgetUserPreferenceTool.TOOL_NAME);

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

    /**
     * 入口智能体 Mapper（REQ-AI-09 扩展：专家执行装配共享入口的长期记忆工作区命名空间）
     */
    private final AiEntryAgentMapper entryAgentMapper;

    private final IAiToolService toolService;

    private final IAiSkillService skillService;

    private final IAiSubagentService subagentService;

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
     * 模型构造工厂：按配置构造对话模型与生成参数、解析各可选模型槽位（供记忆模型解析取用）
     */
    private final AgentModelFactory modelFactory;

    /**
     * 装配指纹计算器：定义与各运行期策略 → 指纹字符串
     */
    private final AgentFingerprintCalculator fingerprintCalculator;

    /**
     * 智能体装配器：给定「定义 + 已解析的装配输入」装配 {@link HarnessAgent} 并生成执行期快照
     * （REQ-AI-28 链路追踪）
     */
    private final AgentAssembler assembler;

    /**
     * @param agentToolMapper    智能体工具关联 Mapper
     * @param agentKbMapper      智能体知识库关联 Mapper
     * @param entryAgentMapper   入口智能体 Mapper（REQ-AI-09 扩展：专家记忆命名空间）
     * @param toolService        业务工具注册 Service
     * @param skillService       技能仓库 Service（绑定关系与启用状态）
     * @param subagentService    子智能体 Service（绑定关系与启用状态，REQ-AI-04）
     * @param permissionModeStore 全局权限模式
     * @param runtimeConfigStore  运行期策略配置（压缩 / 计划模式）
     * @param mcpClientService    外部 MCP 连接（REQ-AI-16）
     * @param sessionResourceService 会话级资源集（REQ-AI-32）
     * @param modelFactory        模型构造工厂
     * @param fingerprintCalculator 装配指纹计算器
     * @param assembler           智能体装配器
     */
    public AgentRegistry(AiAgentToolMapper agentToolMapper,
                         AiAgentKbMapper agentKbMapper,
                         AiEntryAgentMapper entryAgentMapper,
                         IAiToolService toolService,
                         IAiSkillService skillService,
                         IAiSubagentService subagentService,
                         AiPermissionModeStore permissionModeStore,
                         AiRuntimeConfigStore runtimeConfigStore,
                         IAiMcpClientService mcpClientService,
                         IAiSessionResourceService sessionResourceService,
                         AgentModelFactory modelFactory,
                         AgentFingerprintCalculator fingerprintCalculator,
                         AgentAssembler assembler) {
        this.agentToolMapper = agentToolMapper;
        this.agentKbMapper = agentKbMapper;
        this.entryAgentMapper = entryAgentMapper;
        this.toolService = toolService;
        this.skillService = skillService;
        this.subagentService = subagentService;
        this.permissionModeStore = permissionModeStore;
        this.runtimeConfigStore = runtimeConfigStore;
        this.mcpClientService = mcpClientService;
        this.sessionResourceService = sessionResourceService;
        this.modelFactory = modelFactory;
        this.fingerprintCalculator = fingerprintCalculator;
        this.assembler = assembler;
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
        return getInternal(definition, resources, false).agent();
    }

    /**
     * 取用（必要时装配）「智能体 × 会话资源集」实例**并同时返回其执行期快照**。
     *
     * <p>供入口轮使用：链路追踪需要快照挂父链路、本轮执行需要实例，原先二者各走一遍完整装配
     * （`snapshotOf` + `get`），此处合并为**一次装配**，省掉一轮重复的绑定 / 工具 / 知识库 / 技能 /
     * 子智能体 / 运行期策略 / MCP 解析查询（口径见 docs/22 §3.1 C「前提修正」）。</p>
     *
     * <p>快照与实例取自**同一次装配结果**，因此「链路里记录的上下文清单」与「实际执行用的实例」
     * 天然同源——这正是原先 `snapshotOf` 统一走 `get` 所要保证的语义。</p>
     *
     * @param definition 智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @return 装配结果（实例 + 指纹 + 执行期快照）
     */
    public AgentHolder holder(AiAgent definition, AiSessionResourceSet resources) {
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
        return getInternal(definition, resources, true).agent();
    }

    /**
     * 取用（必要时装配）实例的内部实现。
     *
     * @param definition 智能体定义
     * @param resources  会话级资源集（可空：表示空集）
     * @param expertMode 是否专家执行装配（只读强制 + 协议工具，子智能体关闭）
     * @return 装配结果（实例 + 指纹 + 执行期快照）
     */
    private AgentHolder getInternal(AiAgent definition, AiSessionResourceSet resources, boolean expertMode) {
        if (definition == null || definition.getId() == null) {
            throw new ServiceException("智能体定义不存在");
        }
        AiSessionResourceSet res = resources == null ? AiSessionResourceSet.empty() : resources;
        List<AiAgentTool> bindings = queryBindings(definition.getId());
        // 合并决策「智能体绑定 → 包内 → 直接选中（越显式越优先）」后，被禁止的工具不注册进 Toolkit（模型不可见）
        Map<String, String> merged = AgentPermissionContextFactory.mergeToolDecisions(bindings,
            res.mergedToolDecisions());
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
        // 长期记忆工作区命名空间（REQ-AI-09 扩展）：专家执行装配与入口智能体**共享同一份用户记忆**——
        // 记忆按 <workspaceRoot>/<agentCode>/<userId> 隔离，专家若用自身编码则命名空间为空、读不到
        // 该用户的历史记忆。故专家装配统一改用入口智能体编码；入口未配置时回退自身编码。
        final String workspaceCode;
        if (expertMode) {
            String entryCode = entryAgentCode();
            workspaceCode = (entryCode != null && !entryCode.isBlank())
                ? entryCode : definition.getAgentCode();
        } else {
            workspaceCode = definition.getAgentCode();
        }
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
            AgentAssembler.AssembledAgent assembled = assembler.assemble(definition, enabledTools, kbIds, skillNames,
                bindings, res, subagents, compactionPolicy, planPolicy, memoryPolicy,
                workspacePolicy, mcpAssembly, expertMode, workspaceCode);
            return new AgentHolder(definition.getId(), assembled.agent(), fingerprint, assembled.snapshot());
        });
        return holder;
    }

    /**
     * 取入口智能体编码（REQ-AI-09 扩展）。入口恒 1 行；未配置或查询失败时返回 null。
     *
     * @return 入口智能体编码，或 null
     */
    private String entryAgentCode() {
        try {
            AiEntryAgent entry = entryAgentMapper.selectList(Wrappers.lambdaQuery()).stream().findFirst().orElse(null);
            return entry == null ? null : entry.getAgentCode();
        } catch (Exception e) {
            log.warn("解析入口智能体编码失败：{}", e.getMessage());
            return null;
        }
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
        return AgentPermissionContextFactory.buildContext(permissionModeStore.resolve(), bindings,
            res.packToolDecisions(), res.directToolDecisions(), mcpDecisions,
            !subagents.isEmpty(), definition.isEntry(), declaredMemoryToolsActive(definition));
    }

    /**
     * 是否为「平台托管记忆 + 入口智能体」：只有这种装配会注册显式记忆工具。
     *
     * <p>权限基线必须与实际注册的工具集一致：装配期注册了工具而基线未放行，
     * 每轮重建权限上下文后该工具会落到全局默认（未命中即 ASK）。</p>
     *
     * @param definition 智能体定义
     * @return 是否启用显式记忆工具
     */
    private boolean declaredMemoryToolsActive(AiAgent definition) {
        if (definition == null || !definition.isEntry()) {
            return false;
        }
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        return policy != null && policy.isEnabled() && "platform".equals(policy.getPipelineMode());
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
        return AgentPermissionContextFactory.buildContext(permissionModeStore.resolve(),
            queryBindings(definition.getId()), res.packToolDecisions(),
            res.directToolDecisions(), mcpDecisions,
            false, false, false);
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
     * 装配缓存项。
     *
     * @param agentId     所属智能体主键（供按智能体整体失效缓存）
     * @param agent       智能体实例
     * @param fingerprint 定义指纹（含场景包签名）
     * @param snapshot    该次装配的执行期快照
     */
    record AgentHolder(Long agentId, HarnessAgent agent, String fingerprint, AgentSnapshot snapshot) {
    }

}
