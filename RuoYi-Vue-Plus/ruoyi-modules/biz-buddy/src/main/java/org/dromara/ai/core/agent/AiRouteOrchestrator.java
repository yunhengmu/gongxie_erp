package org.dromara.ai.core.agent;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.bo.AiRouteBudgetConfigBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 专家编排器（REQ-AI-30）：把入口智能体的决策落成**一次**专家调用，并处置其可用性。
 *
 * <p><b>职责边界</b>：本类只负责"调专家 + 判可用 + 决定下一步"，**不做事实改写、不做结论仲裁**；
 * <b>专家直答修订（2026-10-03）</b>起，专家的回复**直接交付用户**（流式直传），
 * 入口智能体不再整合、不再有输出契约与重试（详见 docs/20）。</p>
 *
 * <p><b>处置</b>：</p>
 * <ul>
 *   <li>可用（有非空正文）：正文已直传用户，写入 {@code handoffAnswer} 供收尾落库；</li>
 *   <li>不可用（调用失败 / 超时 / 正文为空）：**不重试**，直接降级为入口智能体兜底自答并如实声明；</li>
 *   <li>命中「需确认」工具：暂停（不算失败），用户确认后由恢复段续跑。</li>
 * </ul>
 *
 * <p><b>上下文隔离</b>：专家用独立 {@code sessionId = routeRunId#expertAgentId}，因此框架加载不到用户会话
 * 历史；传给专家的只有一条 user message（任务片段 + 作答要求）。</p>
 *
 * <p><b>权限</b>：专家走独立装配（{@code AgentRegistry#getExpert}）：继承会话资源集，子智能体能力关闭；
 * 权限上下文按 {@code (userId, expertSessionId)} 槽位生效——「需确认」档走框架原生 HITL 暂停，
 * 其余按既有三档授权。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiRouteOrchestrator {

    /**
     * SSE 事件：路由决策
     */
    static final String EVENT_ROUTE_DECISION = "route_decision";

    /**
     * SSE 事件：专家调用开始
     */
    static final String EVENT_EXPERT_START = "expert_start";

    /**
     * SSE 事件：专家结论完成
     */
    static final String EVENT_EXPERT_RESULT = "expert_result";

    /**
     * SSE 事件：降级由入口智能体自答
     */
    static final String EVENT_ROUTE_FALLBACK = "route_fallback";

    /**
     * SSE 事件：清空本轮已展示的正文（专家直答接管时，丢弃入口智能体已流出的文本）
     */
    static final String EVENT_ANSWER_RESET = "answer_reset";

    /**
     * 专家调用同步等待的兜底超时（秒）：运行期配置未给正数时使用
     */
    static final int EXPERT_SYNC_TIMEOUT_SECONDS = 180;

    /**
     * 「需确认」暂停的校验说明（写入 {@code ai_route_expert_call.validation_error} 便于事后核对为何未出结论）
     */
    static final String PAUSE_VALIDATION_ERROR = "命中「需确认」工具，等待用户人工确认后由恢复段续跑";

    /**
     * 用户主动停止本轮的说明文案（落专家调用明细的 validation_error 与链路追踪；与门面 / 整合轮同口径）
     */
    static final String CANCELLED_TEXT = "用户停止生成";

    /**
     * 暂停场景下回给入口智能体的工具结果文本（本轮已暂停，只提示、不再路由）
     */
    static final String PAUSE_TOOL_RESULT =
        "本轮编排已暂停：专家要执行的操作需要用户人工确认后才会继续。"
            + "请只回一句「正在等待你确认」，不要再调用本工具。";

    private final AgentRegistry agentRegistry;

    private final AiExpertDirectory expertDirectory;

    private final AiRouteRecorder recorder;

    private final AiStreamEventBridge streamBridge;

    private final IAiTraceService traceService;

    private final AiProperties aiProperties;

    /**
     * 运行期配置：编排预算（专家轮 Token 预算 / 墙钟预算）可在运行期覆盖，缺省回落 {@link AiProperties.Chat}
     */
    private final AiRuntimeConfigStore runtimeConfigStore;

    private final IAiSkillService skillService;

    /**
     * 智能体 Service：取该用户在该专家上「记住该工具」的信任集合（REQ-AI-30 Phase 2 HITL 贯通）
     */
    private final IAiAgentService agentService;

    /**
     * 单次执行取消注册表：专家调用经此登记句柄，使「停止生成」/ SSE 断连能中断**正在进行**的专家推理
     */
    private final AiRunRegistry runRegistry;

    /**
     * @param agentRegistry    智能体装配注册表（专家走独立装配）
     * @param expertDirectory  专家名录（取专家定义）
     * @param recorder         编排链路落库
     * @param streamBridge     事件桥（把专家内部事件累积进本地状态并写入子链路）
     * @param traceService     链路追踪（每次专家调用一条子链路）
     * @param aiProperties     配置默认值（专家同步等待上限等）
     * @param runtimeConfigStore 运行期配置（编排预算可在运行期覆盖）
     * @param skillService     技能生效范围（专家链路按提问用户过滤可见面）
     * @param agentService     用户级工具信任查询
     * @param runRegistry      单次执行取消注册表（「停止生成」取消专家的进行中调用）
     */
    public AiRouteOrchestrator(@Lazy AgentRegistry agentRegistry,
                               AiExpertDirectory expertDirectory,
                               AiRouteRecorder recorder,
                               AiStreamEventBridge streamBridge,
                               IAiTraceService traceService,
                               AiProperties aiProperties,
                               AiRuntimeConfigStore runtimeConfigStore,
                               IAiSkillService skillService,
                               IAiAgentService agentService,
                               AiRunRegistry runRegistry) {
        this.agentRegistry = agentRegistry;
        this.expertDirectory = expertDirectory;
        this.recorder = recorder;
        this.streamBridge = streamBridge;
        this.traceService = traceService;
        this.aiProperties = aiProperties;
        this.runtimeConfigStore = runtimeConfigStore;
        this.skillService = skillService;
        this.agentService = agentService;
        this.runRegistry = runRegistry;
    }

    /**
     * 执行一条专家编排：调用一次专家，其正文**直传用户**。
     *
     * <p>返回值是**给入口智能体的工具结果文本**：成功时只是一句「专家已直接回答，你不要再输出」；
     * 失败时明确告知"未拿到可靠专家结论"，让它兜底自答并如实说明。</p>
     *
     * @param route 编排运行状态
     * @param first 入口智能体选定的专家
     * @return 工具结果文本
     */
    public String execute(AiRouteContext route, AiExpertCandidate first) {
        return executeInternal(route, first);
    }

    /**
     * 执行一条**指定专家**（固化路由，REQ-AI-31）的专家编排。
     *
     * <p>与 {@link #execute(AiRouteContext, AiExpertCandidate)} 完全同路（调用一次、正文直答、
     * 失败即降级），差别只在专家来源：模型决策 vs 会话指定。</p>
     *
     * <p>指定专家按主键重新解析（候选清单每轮现算，用户可见范围可能变化），解析不到即降级。</p>
     *
     * @param route 编排运行状态（{@code pinnedExpertId} 非空）
     * @return 工具结果文本
     */
    public String executePinned(AiRouteContext route) {
        AiExpertAgent definition = expertDirectory.definition(route.pinnedExpertId);
        AiExpertCandidate candidate = definition == null ? null : candidateOf(definition);
        if (candidate == null) {
            route.degraded = true;
            route.degradeReason = "指定专家不存在或已被删除";
            log.warn("指定专家不可用，直接降级自答：pinnedExpertId={}", route.pinnedExpertId);
            emit(route, EVENT_ROUTE_FALLBACK, Map.of("reason", route.degradeReason));
            return degradeReport(route, null, null);
        }
        return executeInternal(route, candidate);
    }

    /**
     * 执行一条专家编排（自动路由与指定路由共用；只调用一次）。
     *
     * @param route 编排运行状态
     * @param first 选定的专家（自动路由＝入口智能体选择；指定路由＝会话指定的专家）
     * @return 工具结果文本
     */
    private String executeInternal(AiRouteContext route, AiExpertCandidate first) {
        AiExpertCandidate chosen = first;
        emit(route, EVENT_ROUTE_DECISION, Map.of(
            "action", StringUtils.isBlank(route.action) ? AiRouteRun.ACTION_ROUTE : route.action,
            "expertKey", chosen.agentKey(),
            "expertAgentId", chosen.agentId(),
            "expertName", chosen.agentName()));
        // 专家直答修订（2026-10-03）：阶梯恒为单一专家、**只调用一次**——不判契约、不重试、不换专家；
        // 调不通或没有产出即降级为小Z 兜底自答
        if (route.budgetExceeded) {
            route.degraded = true;
            route.degradeReason = budgetDegradeReason(route);
            emit(route, EVENT_ROUTE_FALLBACK, Map.of("reason", route.degradeReason));
            return degradeReport(route, chosen, null);
        }
        ExpertAttempt result = runOnce(route, chosen, 1, AiRouteExpertCall.KIND_FIRST);
        if (result.cancelled()) {
            // 用户停止（「停止生成」/ SSE 断连）：不重试、不降级，由调用方按「已中止」收尾
            return "";
        }
        if (route.pendingResume != null) {
            // 专家命中「需确认」：本轮编排暂停（不算失败），用户确认后由恢复段续跑
            //（REQ-AI-30 Phase 2 HITL 贯通）
            return PAUSE_TOOL_RESULT;
        }
        if (result.ok()) {
            return successReport(chosen);
        }
        route.degraded = true;
        route.degradeReason = "专家结论不可用：" + result.error();
        emit(route, EVENT_ROUTE_FALLBACK, Map.of("reason", route.degradeReason));
        return degradeReport(route, chosen, result.error());
    }

    /**
     * 人工确认后的专家续跑（REQ-AI-30 Phase 2 HITL 贯通）。
     *
     * <p>用 {@code ConfirmResult} 消息驱动**同一个专家实例 + 同一个 {@code expertSessionId}** 继续执行
     * （框架要求续跑必须复用同一 {@code (agentId, sessionId)}）。</p>
     *
     * <p><b>专家直答修订（2026-10-03）</b>：续跑只调用一次——不判契约、不重试；续跑仍失败即降级为小Z 兜底。</p>
     *
     * @param route          编排运行状态（恢复段重建）
     * @param resume         暂存的专家续跑信息（仅用专家主键与会话标识）
     * @param confirmMessages 携带 {@code ConfirmResult} 的恢复消息（由门面用既有逻辑构造）
     * @return 续跑结果：{@code paused} 表示恢复段内**再次**命中「需确认」；否则 {@code report} 为兜底输入（成功时为空提示）
     */
    public ResumeOutcome resumeExpert(AiRouteContext route, AiConfirmPendingStore.ExpertResume resume,
                                      List<Msg> confirmMessages) {
        AiExpertAgent definition = expertDirectory.definition(resume.expertAgentId());
        AiExpertCandidate candidate = definition == null ? null : candidateOf(definition);
        if (candidate == null) {
            route.degraded = true;
            route.degradeReason = "专家不存在或已被删除，无法续跑";
            emit(route, EVENT_ROUTE_FALLBACK, Map.of("reason", route.degradeReason));
            return new ResumeOutcome(false, degradeReport(route, null, null));
        }
        int attempt = resume.attemptNo() == null ? 1 : resume.attemptNo();
        // 用确认结果续跑本次尝试（同一专家会话）；恢复段沿用首段的 attemptNo（审计口径不变）
        ExpertAttempt resumed = runExpert(route, candidate, attempt, AiRouteExpertCall.KIND_RESUME,
            null, confirmMessages);
        if (resumed.cancelled()) {
            // 用户停止：不降级，由恢复段按「已中止」收尾
            return new ResumeOutcome(false, null);
        }
        if (route.pendingResume != null) {
            return new ResumeOutcome(true, null);
        }
        if (resumed.ok()) {
            return new ResumeOutcome(false, successReport(candidate));
        }
        route.degraded = true;
        route.degradeReason = "恢复后专家结论不可用：" + resumed.error();
        emit(route, EVENT_ROUTE_FALLBACK, Map.of("reason", route.degradeReason));
        return new ResumeOutcome(false, degradeReport(route, candidate, resumed.error()));
    }

    /**
     * 定义 → 候选（与名录现算的候选同构，供恢复段按主键重建专家）。
     *
     * @param definition 专家定义
     * @return 候选
     */
    private AiExpertCandidate candidateOf(AiExpertAgent definition) {
        // 专家直答修订（2026-10-03）：不再携带输出契约
        return AiExpertCandidate.of(definition.getId(), definition.getAgentCode(), definition.getAgentName(),
            definition.getExpertDomain(), definition.getCapabilityDesc(), definition.getDescription());
    }

    /**
     * 恢复段续跑的结果。
     *
     * @param paused 是否再次命中「需确认」（再次暂停，不驱动收尾）
     * @param report 给入口智能体的输入（成功时为空提示；{@code paused=true} 时为 null）
     */
    public record ResumeOutcome(boolean paused, String report) {
    }

    /**
     * 把一次专家调用的 Token 计入编排预算，并即时判定是否触顶（REQ-AI-30 Phase 2 第四项）。
     *
     * <p>「暂停」的那一次调用同样消耗了模型配额，故一并计入；判定为**事后**（调用结束后）——
     * 单次花费无法预估，触顶即由调用方停止后续调用。</p>
     *
     * @param route 编排运行状态
     * @param usage 本次调用的 Token 用量（可空）
     */
    private void accumulateExpertTokens(AiRouteContext route, ChatTokenUsage usage) {
        if (usage != null) {
            route.expertPromptTokens.addAndGet(usage.promptTokens());
            route.expertCompletionTokens.addAndGet(usage.completionTokens());
        }
        // 预算走运行期配置（Redis 覆盖优先，缺省回落 AiProperties.Chat），保存后下一次编排即生效。
        // **记账是尽力而为**：Redis 读失败（含线程遗留中断态导致的 RedisException）只告警、不外抛——
        // 否则会连累其后的取消收尾，把「已取消」审计明细一并丢掉（实测约半数 L13 失败）。
        AiRouteBudgetConfigBo budget;
        try {
            budget = runtimeConfigStore.routeBudget();
        } catch (Exception e) {
            log.warn("编排预算读取失败，本轮跳过预算判定（不影响审计）：sessionId={}", route.sessionId, e);
            return;
        }
        long tokens = route.expertPromptTokens.get() + route.expertCompletionTokens.get();
        if (budget.getExpertTokens() > 0 && tokens >= budget.getExpertTokens()) {
            route.budgetExceeded = true;
        }
        int wallBudget = budget.getWallSeconds();
        if (wallBudget > 0 && elapsedSeconds(route) >= wallBudget) {
            route.budgetExceeded = true;
        }
    }

    /**
     * 编排已耗费的墙钟秒数（HITL 恢复段按恢复段自身重新计时，不跨请求累计）。
     *
     * @param route 编排运行状态
     * @return 秒数
     */
    private long elapsedSeconds(AiRouteContext route) {
        return (System.nanoTime() - route.startedNanos) / 1_000_000_000L;
    }

    /**
     * 预算触顶的降级原因（写明实际值与上限，便于在「编排运行」页直接看到）。
     *
     * @param route 编排运行状态
     * @return 形如「超出编排预算：专家轮 Token 累计 12,345 ≥ 上限 10,000（…）」的文本
     */
    private String budgetDegradeReason(AiRouteContext route) {
        AiRouteBudgetConfigBo budget = runtimeConfigStore.routeBudget();
        List<String> parts = new ArrayList<>(2);
        long tokens = route.expertPromptTokens.get() + route.expertCompletionTokens.get();
        if (budget.getExpertTokens() > 0 && tokens >= budget.getExpertTokens()) {
            parts.add("专家轮 Token 累计 " + tokens + " ≥ 上限 " + budget.getExpertTokens());
        }
        int wallBudget = budget.getWallSeconds();
        if (wallBudget > 0 && elapsedSeconds(route) >= wallBudget) {
            parts.add("编排墙钟 " + elapsedSeconds(route) + "s ≥ 上限 " + wallBudget + "s");
        }
        return "超出编排预算（" + String.join("；", parts) + "），已停止继续调用专家";
    }

    /**
     * 构造专家调用的运行期上下文（首次调用与人工确认后的续跑共用）。
     *
     * @param route          编排运行状态
     * @param candidate      专家
     * @param expertSessionId 专家会话标识（{@code routeRunId#expertAgentId}，续跑必须完全一致）
     * @return 运行期上下文
     */
    private RuntimeContext buildExpertContext(AiRouteContext route, AiExpertCandidate candidate,
                                              String expertSessionId) {
        return RuntimeContext.builder()
            .userId(String.valueOf(route.userId))
            .sessionId(expertSessionId)
            // 技能生效范围（REQ-AI-10）：与对话 / 无人值守链路同口径按**提问用户**过滤，
            // 否则专家会看到自己装配的全部技能、绕过技能的生效范围；
            // 专家**继承会话资源集**（REQ-AI-32），故附加技能取资源集内技能（包内 ∪ 直接选中）
            .put(SkillFilter.class, runtimeSkillFilter(candidate.agentId(), route.resources, route.userId))
            .build();
    }

    /**
     * 应用专家链路的权限口径。
     *
     * <p>模式恒为 {@link PermissionMode#DONT_ASK}（未命中显式规则的工具一律拒绝，不悬挂）。
     * <b>未开启写工具开关</b>的专家额外把该槽位上的显式 ask 改写为 deny（Phase 1 口径，专家纯分析型）；
     * <b>开启开关</b>时**保留 ask**——否则「需确认」档不会触发框架暂停，编排链路的 HITL 就无从贯通。</p>
     *
     * <p><b>无人值守</b>（{@link AiRouteContext#unattended}，定时任务 / 渠道接入）：没有人类可确认，
     * 故**无视写工具开关**，一律把 ask 改写为 deny，杜绝 HITL 悬挂。</p>
     *
     * @param expert         已装配的专家实例
     * @param definition     专家定义（判写工具开关）
     * @param context        本次调用的运行期上下文
     * @param route          编排运行状态（取提问用户与是否无人值守）
     * @param expertSessionId 专家会话标识
     */
    private void applyExpertPermission(HarnessAgent expert, AiExpertAgent definition, RuntimeContext context,
                                       AiRouteContext route, String expertSessionId) {
        expert.setPermissionMode(context, PermissionMode.DONT_ASK);
        if (route.unattended || !definition.expertWriteAllowed()) {
            AiPermissionSupport.denyAskingTools(expert, String.valueOf(route.userId), expertSessionId);
        }
    }

    /**
     * 专家命中「需确认」工具：把暂停**上浮**为入口轮的待确认，并记下续跑上下文。
     *
     * <p>框架暂停的是专家自己的会话（{@code (userId, expertSessionId)}），用户看到的是入口轮，
     * 故把专家侧的待确认信息写入入口轮状态，由 {@link AiTurnFinisher} 的收尾逻辑落「待确认」助手消息
     * 并下发确认卡片；用户确认后由 {@link #resumeExpert} 用 {@code ConfirmResult} 驱动同一专家续跑。</p>
     *
     * @param route          编排运行状态
     * @param candidate      专家
     * @param attempt        当前尝试序号
     * @param kind           当前调用类型
     * @param state          专家本轮累积状态（含框架给的待确认工具调用原文）
     * @param expertSessionId 专家会话标识
     * @param durationMs     本轮专家调用耗时（毫秒）
     */
    private void pauseOnConfirm(AiRouteContext route, AiExpertCandidate candidate, int attempt,
                                String kind, AiStreamState state,
                                String expertSessionId, long durationMs) {
        AiStreamState entry = route.entryState;
        if (entry != null) {
            entry.pendingConfirm = true;
            entry.pendingAgentId = candidate.agentId();
            entry.pendingReplyId = StringUtils.isNotBlank(state.pendingReplyId)
                ? state.pendingReplyId : state.replyId;
            entry.pendingToolCalls = state.pendingToolCalls;
        }
        route.pendingResume = new AiConfirmPendingStore.ExpertResume(route.routeRunId, candidate.agentId(),
            expertSessionId, route.taskContext, attempt, kind);
        String raw = AiSseSupport.abbreviate(state.answer.toString(), 8000);
        AiRouteExpertCall call = new AiRouteExpertCall();
        call.setRouteRunId(route.routeRunId);
        call.setExpertAgentId(candidate.agentId());
        call.setExpertSessionId(expertSessionId);
        call.setAttemptNo(attempt);
        call.setCallKind(kind);
        call.setRawOutput(raw);
        call.setValidationError(PAUSE_VALIDATION_ERROR);
        call.setStatus(AiRouteExpertCall.STATUS_PENDING_CONFIRM);
        ChatTokenUsage usage = state.toTokenUsage();
        if (usage != null) {
            call.setPromptTokens((long) usage.promptTokens());
            call.setCompletionTokens((long) usage.completionTokens());
        }
        call.setDurationMs(durationMs);
        call.setCreateBy(route.userId);
        recorder.recordExpertCall(call);
        traceService.accumulate(state.traceId, AiTrace.STATUS_PENDING_CONFIRM, durationMs, state.tools.size(),
            usage == null ? null : usage.promptTokens(),
            usage == null ? null : usage.completionTokens(),
            usage == null ? null : usage.totalTokens(), null);
        traceService.appendTransition(state.traceId, "pending_confirm",
            "专家命中「需确认」工具：" + state.pendingToolCalls.stream()
                .map(AiConfirmPendingStore.PendingToolCall::toolName).toList());
        // 此处**刻意不下发 expert_result**：下发了会把卡片标成终态，于是恢复段真正的 expert_result
        // 就找不到可回填的卡片了。卡片保持「调用中」，由确认卡片说明当前状态。
    }

    /**
     * 把框架的「等待人工确认」异常翻译成可读提示。
     *
     * <p>典型场景：Redis 暂存已过期（或被另一路恢复消费）而框架侧仍处于 ASKING，此时续跑会抛
     * {@code IllegalStateException(Agent is paused for human-in-the-loop confirmation ...)}。</p>
     *
     * @param error 原始错误说明（可空）
     * @return 翻译后的说明
     */
    private String translatePausedStateError(String error) {
        if (error != null && error.contains("human-in-the-loop confirmation")) {
            return "该确认已过期或已被处理，专家会话状态不再可用；请重新提问。";
        }
        return error;
    }

    /**
     * 执行一次专家调用：独立会话、强制同步等待、按契约校验，并把结果落库与下发事件。
     *
     * @param route         编排运行状态
     * @param candidate     本次调用的专家
     * @param attempt       第几次尝试（从 1 起）
     * @param kind          调用类型（first / resume）
     * @return 本次尝试结果
     */
    private ExpertAttempt runOnce(AiRouteContext route, AiExpertCandidate candidate,
                                  int attempt, String kind) {
        return runExpert(route, candidate, attempt, kind,
            buildExpertMsg(route), null);
    }

    /**
     * 专家调用的统一实现：独立会话、**可取消**的同步等待、正文直传用户，并把结果落库与下发事件。
     *
     * <p><b>可取消</b>（docs/00 第 1 项）：执行经 {@code expert.prepareRun(...)} 取得
     * {@code AgentRun}，把 {@code run::cancel} 登记进 {@link AiRunRegistry}；
     * 用户「停止生成」或 SSE 断连即中断**正在进行**的模型调用与工具循环，本方法随即将
     * {@code route.cancelled} 置位并返回「已取消」，由调用方按「已中止」收尾（不降级）。</p>
     *
     * <p>{@code resumeMessages} 非空时表示这是**人工确认后的续跑**（REQ-AI-30 Phase 2 HITL 贯通）：
     * 用 {@code ConfirmResult} 消息驱动**同一个专家会话**继续执行，不再重复下发 {@code expert_start}
     * （前端确认后复用同一条助手消息、其专家卡片仍处于「调用中」，重复下发会多出一张孤儿卡片）。</p>
     *
     * @param route          编排运行状态
     * @param candidate      本次调用的专家
     * @param attempt        第几次尝试（从 1 起）
     * @param kind           调用类型（first / resume）
     * @param userMessage    首次调用下发的 user message（续跑时为 null）
     * @param resumeMessages 人工确认后的续跑消息（首次调用时为 null）
     * @return 本次尝试结果
     */
    private ExpertAttempt runExpert(AiRouteContext route, AiExpertCandidate candidate,
                                    int attempt, String kind,
                                    Msg userMessage, List<Msg> resumeMessages) {
        boolean resume = resumeMessages != null;
        AiExpertAgent definition = expertDirectory.definition(candidate.agentId());
        if (definition == null) {
            return ExpertAttempt.unrepairable("专家不存在或已被删除");
        }
        String expertSessionId = route.routeRunId + "#" + candidate.agentId();
        if (!resume) {
            emit(route, EVENT_EXPERT_START, Map.of(
                "expertKey", candidate.agentKey(),
                "expertAgentId", candidate.agentId(),
                "expertName", candidate.agentName(),
                "attemptNo", attempt,
                "callKind", kind));
            // 对话期进度：专家执行可能长达数十秒，先播报「在咨询谁」，其内部动作由专家状态的 phase 接续
            AiPhaseSupport.emit(route.emitter, route.entryState, AiPhaseSupport.PHASE_EXPERT,
                "正在咨询领域专家「" + candidate.agentName() + "」…", null);
        }
        // 专家直答（REQ-AI-30 直答修订，2026-10-03）：先清掉小Z 已流出的正文并进入接管态，
        // 随后专家的正文以 text_delta 直传用户（内部工具卡片不下发，只累积留证）
        beginHandoff(route);
        Long childTraceId = traceService.start(route.sessionId, candidate.agentId(), route.userId,
            AiTrace.SOURCE_CHAT, agentRegistry.snapshotOfExpert(definition, route.resources), route.traceId);
        AiStreamState state = new AiStreamState();
        state.sessionId = route.sessionId;
        state.userId = route.userId;
        state.agentId = candidate.agentId();
        state.modelId = definition.getModelId();
        state.traceId = childTraceId;
        // 对话期进度：专家的思考 / 工具动作按专家身份播报（「专家「X」正在调用…」）
        state.speakerExpertName = candidate.agentName();
        // 专家继承会话资源集（REQ-AI-32）：与小Z 同源的工具 / 技能 / MCP 工具可见面（专家侧仅只读 + 会话选中的只读工具）
        HarnessAgent expert = agentRegistry.getExpert(definition, route.resources);
        RuntimeContext context = buildExpertContext(route, candidate, expertSessionId);
        // 用户级「记住该工具」信任（REQ-AI-30 Phase 2 HITL 贯通）：专家每轮的槽位键都是新的
        // （routeRunId#expertAgentId），故必须按**专家装配基线**把该用户的信任并入本槽位，
        // 否则「记住」后下一轮仍会弹确认卡片。顺序在 setPermissionMode 之前：
        // 合并会以基线模式重建上下文，模式必须最后由 applyExpertPermission 钉为 DONT_ASK。
        AiPermissionSupport.applyUserToolTrust(expert,
            agentRegistry.permissionContextOfExpert(definition, route.resources),
            agentService.trustedToolCodes(definition.getId(), route.userId),
            String.valueOf(route.userId), expertSessionId);
        applyExpertPermission(expert, definition, context, route, expertSessionId);

        long startNanos = System.nanoTime();
        // 可取消的专家执行（AgentScope 2.0.4 AgentRun）：登记句柄后，「停止生成」/ SSE 断连可中断
        // **正在进行**的模型调用与工具循环（与入口轮同款口径；原先是裸 streamEvents + blockLast，
        // 同步阻塞且无 Disposable 可登记，导致短路轮 / 指定专家兜底 / 恢复段都停不下来）
        AgentRun<AgentEvent> run = expert.prepareRun(resume ? resumeMessages : List.of(userMessage), context);
        AtomicBoolean externalCancel = new AtomicBoolean(false);
        AiRunRegistry.Cancelable cancelable = () -> {
            externalCancel.set(true);
            return run.cancel();
        };
        runRegistry.register(route.sessionId, cancelable);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        boolean finishedInTime = true;
        boolean interrupted = false;
        try {
            run.stream()
                // 正文直传用户、工具事件不下发（专家直答）；emitter 为 null（无人值守）时退化为只累积
                .doOnNext(event -> streamBridge.handle(route.emitter, state, event, false))
                .doFinally(signal -> runRegistry.unregister(route.sessionId, cancelable))
                .subscribe(ignored -> {
                }, e -> {
                    failure.set(AiSseSupport.rootCauseMessage(e));
                    settled.countDown();
                }, settled::countDown);
            // 同步等待（与原 blockLast 同口径）：等待超时即取消该 run，归因见下方
            finishedInTime = settled.await(expertTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finishedInTime) {
                run.cancel();
            }
        } catch (InterruptedException e) {
            // 等待被中断＝**外部取消**：入口轮被取消时 Reactor 会 dispose 本任务（`RouteDecisionTool` 里
            // subscribeOn(boundedElastic) 的阻塞专家调用）并中断其等待；而本方法自己的「等超时」走的是
            // await 返回 false，不会中断。故中断一律按取消归因。
            // **刻意不重新置中断标志**——池化 worker 上遗留中断态会让同一任务后续的 Redis / DB 调用失败，
            // 且异常会被上层 onErrorResume 静默吞掉（实测「进了取消分支却一行明细都没落库」）。
            interrupted = true;
            run.cancel();
        } catch (Exception e) {
            // 订阅异常（例如同一 AgentRun 被重复订阅）：先注销句柄，避免残留
            runRegistry.unregister(route.sessionId, cancelable);
            failure.set(AiSseSupport.rootCauseMessage(e));
        }
        // 取消归因：① 句柄被外部（停止 / 断连）调用过；② 等待被中断（同上，外部取消的副产物）。
        // **不看 run.status()**——等待超时同样会把 run 置为 CANCELLED，按状态判定会把「超时」误标成「用户取消」
        boolean cancelled = externalCancel.get() || interrupted;
        boolean timeout = false;
        String error = null;
        if (cancelled) {
            log.info("专家调用被用户停止：expert={}, attempt={}", candidate.agentKey(), attempt);
        } else if (!finishedInTime) {
            timeout = true;
            error = translatePausedStateError("专家调用超时（" + expertTimeoutSeconds() + " 秒内未完成）");
            log.warn("专家调用失败：expert={}, attempt={}, 原因={}", candidate.agentKey(), attempt, error);
        } else {
            error = translatePausedStateError(failure.get());
            if (error != null) {
                log.warn("专家调用失败：expert={}, attempt={}, 原因={}", candidate.agentKey(), attempt, error);
            }
        }
        // 参考来源（知识库命中）：专家使用**独立的流式状态**，其内部检索命中的来源若只留在该状态会随调用结束丢弃；
        // 此处回填到入口轮状态（气泡下方「参考来源」与落库均读入口状态），失败/超时也回填已命中的部分
        if (route.entryState != null) {
            streamBridge.mergeSources(route.entryState, state.sources);
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        route.expertCallCount.incrementAndGet();
        // 专家内部执行的工具调用（含被拒绝的写操作）累积到编排上下文，收尾时随入口轮明细一起落库：
        // 这是「专家写操作可审计」的载体（REQ-AI-30 Phase 2）
        route.expertToolLogs.addAll(state.toToolLogs(
            aiProperties.getChat().getToolOutputPreviewChars(), candidate.agentId()));
        // 编排预算（Phase 2 第四项）：专家轮 Token 累计 + 触顶判定。
        // 「暂停」的那一次调用同样消耗了模型配额，故在暂停分支之前累计
        accumulateExpertTokens(route, state.toTokenUsage());

        // 用户停止（「停止生成」/ SSE 断连）：不判契约、不置降级、不发 route_fallback，
        // 调用明细记「已取消」、子链路记「已中止」；置 route.cancelled 后交调用方按「已中止」收尾
        if (cancelled) {
            // **取消收尾的硬约束：审计明细不可被跳过**。外层 dispose 对 boundedElastic 工作线程的中断是
            // **异步送达**的——可能落在取消判定之后、甚至落在随后的 Redis / DB 收尾调用上（点清一次收不
            // 干净，实测仍约半数失败）。故：① 清中断态收窄窗口；② 把 recordCall 提到一切 Redis 记账与
            // 其他收尾之前，只依赖内存态；③ 子链路记账包 try/catch，失败只告警不外抛。
            // 取消信号已由 externalCancel / interrupted 承载，清中断态不影响归因。
            Thread.interrupted();
            ChatTokenUsage cancelledUsage = state.toTokenUsage();
            recordCall(route, candidate, expertSessionId, attempt, kind, null, false, CANCELLED_TEXT,
                false, true, cancelledUsage, durationMs);
            route.cancelled = true;
            try {
                accumulateTrace(state, childTraceId, false, CANCELLED_TEXT, false, cancelledUsage, durationMs);
            } catch (Exception e) {
                log.warn("取消收尾的子链路记账失败（不影响专家调用明细）：expert={}, attempt={}",
                    candidate.agentKey(), attempt, e);
            }
            return ExpertAttempt.stopped();
        }

        // 命中「需确认」工具：框架暂停了专家会话，把暂停上浮为入口轮的待确认
        //（编排链路的 HITL 贯通），本函数返回的结果不会被使用——调用方见 pendingResume 即返回
        if (error == null && state.pendingConfirm) {
            pauseOnConfirm(route, candidate, attempt, kind, state, expertSessionId, durationMs);
            return ExpertAttempt.unrepairable("等待人工确认");
        }

        // 专家直答（REQ-AI-30 直答修订，2026-10-03）：不判契约，只判「是否有可用正文」。
        // 调用失败 / 超时 / 正文为空 → 判不可用，由调用方降级为小Z 兜底自答。
        String answerText = state.answer.toString();
        boolean ok = error == null && StringUtils.isNotBlank(answerText);
        String reason = ok ? null
            : error != null ? "专家调用失败：" + error : "专家未产出任何内容";
        // raw_output 原文留证：专家完整正文（超长截断）
        String rawOutput = AiSseSupport.abbreviate(answerText, 8000);
        if (ok) {
            // 直答成功：完整正文写入接管缓冲（收尾落库取它，而非入口轮正文）
            route.handoffAnswer.setLength(0);
            route.handoffAnswer.append(answerText);
        } else {
            // 降级：撤销接管态，让小Z 的兜底正文可以正常流出
            route.handoff = false;
            log.warn("专家结论不可用，转入降级：expert={}, attempt={}, 原因={}",
                candidate.agentKey(), attempt, reason);
        }
        ChatTokenUsage usage = state.toTokenUsage();
        recordCall(route, candidate, expertSessionId, attempt, kind, rawOutput, ok, reason,
            timeout, false, usage, durationMs);
        accumulateTrace(state, childTraceId, ok, reason, timeout, usage, durationMs);
        emit(route, EVENT_EXPERT_RESULT, Map.of(
            "expertKey", candidate.agentKey(),
            "expertAgentId", candidate.agentId(),
            "expertName", candidate.agentName(),
            "attemptNo", attempt,
            "status", ok ? AiRouteExpertCall.STATUS_SUCCESS : AiRouteExpertCall.STATUS_INVALID));
        return new ExpertAttempt(ok, !ok, rawOutput, reason, false);
    }

    /**
     * 进入「专家直答」接管态（REQ-AI-30 直答修订）。
     *
     * <p>清空入口智能体已累积的正文并下发 {@code answer_reset}，前端据此清掉已显示的小Z 文本
     * （保留专家 / 路由卡片）；此后门面丢弃入口轮的正文增量，最终回答由专家正文承担。</p>
     *
     * @param route 编排运行状态
     */
    private void beginHandoff(AiRouteContext route) {
        route.handoff = true;
        if (route.entryState != null) {
            route.entryState.answer.setLength(0);
        }
        emit(route, EVENT_ANSWER_RESET, Map.of());
    }

    /**
     * 落一次专家调用明细（含 raw_output 原文，供来源卡片与事后追溯）。
     *
     * @param route          编排运行状态
     * @param candidate      专家
     * @param expertSessionId 专家独立会话标识
     * @param attempt        尝试序号
     * @param kind           调用类型
     * @param rawOutput      专家输出正文（原文留证）
     * @param ok             是否拿到可用正文
     * @param reason         不可用原因（成功时为空）
     * @param timeout        是否超时
     * @param cancelled      是否被用户停止（「停止生成」/ SSE 断连）
     * @param usage          Token 用量
     * @param durationMs     耗时
     */
    private void recordCall(AiRouteContext route, AiExpertCandidate candidate, String expertSessionId,
                            int attempt, String kind, String rawOutput, boolean ok, String reason,
                            boolean timeout, boolean cancelled, ChatTokenUsage usage, long durationMs) {
        AiRouteExpertCall call = new AiRouteExpertCall();
        call.setRouteRunId(route.routeRunId);
        call.setExpertAgentId(candidate.agentId());
        call.setExpertSessionId(expertSessionId);
        call.setAttemptNo(attempt);
        call.setCallKind(kind);
        call.setRawOutput(rawOutput);
        call.setValidationError(ok ? null : reason);
        call.setStatus(resolveCallStatus(ok, timeout, cancelled));
        if (usage != null) {
            call.setPromptTokens((long) usage.promptTokens());
            call.setCompletionTokens((long) usage.completionTokens());
        }
        call.setDurationMs(durationMs);
        call.setCreateBy(route.userId);
        recorder.recordExpertCall(call);
    }

    /**
     * 归一出专家调用状态（专家直答修订，2026-10-03）。
     *
     * @param ok        是否拿到可用正文
     * @param timeout   是否超时
     * @param cancelled 是否被用户停止（「停止生成」/ SSE 断连）
     * @return 状态取值
     */
    private String resolveCallStatus(boolean ok, boolean timeout, boolean cancelled) {
        if (cancelled) {
            return AiRouteExpertCall.STATUS_CANCELLED;
        }
        if (ok) {
            return AiRouteExpertCall.STATUS_SUCCESS;
        }
        return timeout ? AiRouteExpertCall.STATUS_TIMEOUT : AiRouteExpertCall.STATUS_FAILED;
    }

    /**
     * 累加专家子链路（耗时 / Token / 状态；失败节点写错误原因）。
     *
     * @param state       专家调用的流式状态
     * @param traceId     子链路主键
     * @param ok          是否拿到可用正文
     * @param reason      不可用原因（成功时为空）
     * @param timeout     是否超时
     * @param usage       Token 用量
     * @param durationMs  耗时
     */
    private void accumulateTrace(AiStreamState state, Long traceId, boolean ok, String reason,
                                 boolean timeout, ChatTokenUsage usage, long durationMs) {
        String status = ok ? AiTrace.STATUS_SUCCESS
            : timeout ? AiTrace.STATUS_FAILED : AiTrace.STATUS_ABORTED;
        traceService.accumulate(traceId, status, durationMs, state.tools.size(),
            usage == null ? null : usage.promptTokens(),
            usage == null ? null : usage.completionTokens(),
            usage == null ? null : usage.totalTokens(),
            ok ? null : reason);
    }

    /**
     * 构造成功报告（专家直答修订，REQ-AI-30）：专家已把回复**直接交付用户**，
     * 回喂入口智能体的只是一句「不要再输出」的指令——正文由专家承担，避免小Z 重复或改写。
     *
     * @param candidate 专家
     * @return 工具结果文本
     */
    private String successReport(AiExpertCandidate candidate) {
        return "【专家已直接回答用户】来源：" + sourceTag(candidate) + "。"
            + "本轮不要再输出任何内容，也不要把专家的话重复一遍；直接结束本轮即可。";
    }

    /**
     * 构造降级报告：明确"未拿到可靠专家结论"，并要求入口智能体如实说明、不得编造。
     *
     * @param route     编排运行状态（可空）
     * @param candidate 最后尝试的专家（可能为空）
     * @param reason    专家不可用原因（可空）
     * @return 工具结果文本
     */
    private String degradeReport(AiRouteContext route, AiExpertCandidate candidate, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("【专家结论不可用】");
        if (candidate != null) {
            sb.append("已尝试专家：").append(sourceTag(candidate)).append("。");
        }
        sb.append('\n');
        // 编排侧原因（预算触顶 / 专家不存在等）需原样告知用户，故一并回喂
        if (route != null && StringUtils.isNotBlank(route.degradeReason)) {
            sb.append("编排侧原因：").append(route.degradeReason).append('\n');
        }
        if (StringUtils.isNotBlank(reason)) {
            sb.append("专家不可用原因：").append(reason).append('\n');
        }
        sb.append("\n请如实告知用户「未拿到可靠的专家结论」，然后基于你自己掌握的信息回答；")
            .append("**不要编造专家结论**。");
        return sb.toString();
    }

    /**
     * 渲染来源标签。
     *
     * @param candidate 专家
     * @return 形如 {@code 财务专家（finance_agent）} 的标签
     */
    private String sourceTag(AiExpertCandidate candidate) {
        return candidate.agentName() + "（" + candidate.agentKey() + "）";
    }

    /**
     * 构造专家的 user message（专家直答修订，REQ-AI-30）：任务片段 + 直接作答要求。
     *
     * <p>不再下发输出契约、不再要求结构化提交——专家的回答会**直接展示给用户**，
     * 故只要求「用自然语言把问题答清楚」，并保留两条安全纪律（DENIED 不重试、写操作要点明）。</p>
     *
     * <p><b>写操作纪律（2026-10-04 修正）</b>：写操作由专家**直接调用写工具**、由平台 HITL
     * 暂停确认来保证安全，不再要求「无法回滚就不执行」。旧措辞会让专家对不可回滚的写操作
     * （如发布公告并向在线用户广播）自我否决、只做文字说明，导致写工具从不被调用、
     * 人工确认卡也不出现（实测：2026-10-04「发个通知」链路）。</p>
     *
     * @param route 编排运行状态
     * @return user message 文本
     */
    private String buildExpertMessage(AiRouteContext route) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务片段】\n")
            .append(StringUtils.isBlank(route.taskContext) ? "（未提供，请尽量给出可核实的结论）" : route.taskContext)
            .append("\n\n");
        sb.append("【作答要求】\n")
            .append("1. 你的回答会**直接展示给用户**，请**用自然语言**把问题答清楚，不要输出 JSON /")
            .append("结构化提交格式，不要编造工具结果，也不要写「已提交」之类的话。\n")
            .append("2. 只依据上面给出的事实与你自己查询到的结果作答；信息不足就**如实说明缺什么**，")
            .append("绝不为了显得完整而编造。\n")
            .append("3. 若某个工具返回 DENIED（该操作在编排链路不被放行）：**不要重复调用**，")
            .append("如实说明「该操作未执行、需人工确认」即可，不要改用其它工具绕过。\n")
            .append("4.【写操作必须真的调用，不能只说不做】用户的指令本身就是授权：需要写操作")
            .append("（新增 / 修改 / 删除，如发布公告）时，**必须直接调用对应的写工具**并把参数一次填好；")
            .append("「需要用户确认」指的是**平台会在工具被调用时自动弹出人工确认卡**，不是在回答里问用户。")
            .append("**严禁**只输出「我将为您创建…」「请确认后我将执行」这类文字而不调用工具——那等于什么都没做。")
            .append("若平台返回「等待用户人工确认」，说明确认卡已弹出，本轮到此为止即可。")
            .append("写操作成功后，必须**明确点出已执行的操作**（做了什么、对象是谁）。\n")
            .append("5. 若某次调用后本轮被判为「等待用户人工确认」而暂停：不要重复调用该工具、不要改用其它工具绕过，")
            .append("等用户确认后系统会自动让你继续。\n")
            .append("6. 若某次写工具调用返回「已跳过重复执行」：说明该操作**本次任务中已成功执行过**，")
            .append("直接复用上一次调用的结果作答即可，**不要**换参数或换工具重复执行。\n");
        return sb.toString();
    }

    /**
     * 构造下发给专家的 user message：任务片段文本 + 本轮用户附件内容块（图片 / 文档）。
     *
     * <p>专家用独立会话、拿不到用户会话历史，附件（如图片）若只留在入口轮，专家就"看不到"；
     * 故这里把 {@link AiRouteContext#attachmentBlocks} 与任务片段一并下发。</p>
     *
     * @param route 编排运行状态
     * @return 专家 user message
     */
    private Msg buildExpertMsg(AiRouteContext route) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(buildExpertMessage(route)).build());
        if (route.attachmentBlocks != null && !route.attachmentBlocks.isEmpty()) {
            blocks.addAll(route.attachmentBlocks);
        }
        return new UserMessage(blocks);
    }

    /**
     * 构造专家链路的运行期技能白名单（REQ-AI-10）。
     *
     * <p>与对话 / 无人值守链路同口径：取「专家绑定技能 ∪ 资源集技能 ∩ 启用 ∩ **提问用户可见**」。
     * 专家**继承会话资源集**（REQ-AI-32），故附加技能取资源集内技能
     * （包内 ∪ 直接选中）；不注入该过滤器时专家会看到自己装配的全部技能，从而绕过技能的生效范围（可见面越权）。</p>
     *
     * @param agentId   专家智能体主键
     * @param resources 会话级资源集（可空）
     * @param userId    提问用户主键
     * @return 技能过滤器
     */
    private SkillFilter runtimeSkillFilter(Long agentId, AiSessionResourceSet resources, Long userId) {
        List<Long> extraSkillIds = resources == null ? List.of() : resources.mergedSkillIds();
        List<String> names = skillService.resolveRuntimeSkillFilterKeys(agentId, extraSkillIds, userId);
        return names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names.toArray(new String[0]));
    }

    /**
     * 取本次专家调用的同步等待上限（秒）：运行期配置优先，未给正数时用兜底值。
     *
     * <p>之所以做成配置项：专家循环通常需要两轮模型调用（先按需调工具、再提交结论），
     * 平台默认对话模型的单次响应上限为 60 秒，固定的 120 秒上限会让正常的慢响应被误判为
     * 超时并降级（实测出现过），故留出可调余量。</p>
     *
     * @return 超时秒数
     */
    private int expertTimeoutSeconds() {
        int configured = aiProperties.getChat().getExpertTimeoutSeconds();
        return configured > 0 ? configured : EXPERT_SYNC_TIMEOUT_SECONDS;
    }

    /**
     * 下发一条编排 SSE 事件（无发射器时丢弃：无人值守链路 Phase 1 不启用编排）。
     *
     * @param route 编排运行状态
     * @param type  事件类型
     * @param extra 附加字段
     */
    private void emit(AiRouteContext route, String type, Map<String, Object> extra) {
        if (route.emitter == null) {
            return;
        }
        Map<String, Object> payload = AiSseSupport.envelope(type);
        payload.putAll(extra);
        AiSseSupport.send(route.emitter, payload);
    }

    /**
     * 一次专家尝试的结果（专家直答修订，2026-10-03）。
     *
     * @param ok        是否拿到可用正文（专家已直接交付用户）
     * @param failed    是否失败（失败即降级，由入口智能体兜底自答）
     * @param rawOutput 专家输出正文（原文留证）
     * @param error     不可用原因（成功时为空）
     * @param cancelled 是否被用户停止（「停止生成」/ SSE 断连）：为真时不降级、不产报告
     */
    private record ExpertAttempt(boolean ok, boolean failed, String rawOutput, String error,
                                 boolean cancelled) {

        /**
         * 失败的尝试（专家不存在 / 调用异常 / 无产出）。
         *
         * @param reason 失败原因
         * @return 尝试结果
         */
        static ExpertAttempt unrepairable(String reason) {
            return new ExpertAttempt(false, true, null, reason, false);
        }

        /**
         * 被用户停止的尝试（「停止生成」/ SSE 断连）：既不算成功也不算失败，
         * 由调用方按「已中止」收尾，**不得**降级为小Z 兜底自答。
         *
         * @return 尝试结果
         */
        static ExpertAttempt stopped() {
            return new ExpertAttempt(false, false, null, CANCELLED_TEXT, true);
        }
    }
}
