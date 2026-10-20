package org.dromara.ai.core.agent;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.dto.AiTaskExecution;
import org.dromara.ai.domain.bo.AiChatConfirmBo;
import org.dromara.ai.domain.vo.AiChatPendingVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.ai.service.IAiQuotaService;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 对话门面：负责会话编排并把 AgentScope 事件流桥接为统一的 SSE 事件信封。
 *
 * <p>会话 ID 即 {@code ai_session.id}（雪花 ID），作为 AgentScope 的 sessionId 使用，
 * 断线后按同一会话继续提问可续接同一段记忆。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatFacade {

    /**
     * 事件类型：开始
     */
    private static final String EVENT_START = "start";

    /**
     * 事件类型：正文增量
     */
    private static final String EVENT_TEXT_DELTA = "text_delta";

    /**
     * 事件类型：工具调用开始
     */
    private static final String EVENT_TOOL_CALL_START = "tool_call_start";

    /**
     * 事件类型：工具入参增量
     */
    private static final String EVENT_TOOL_CALL_DELTA = "tool_call_delta";

    /**
     * 事件类型：工具结果
     */
    private static final String EVENT_TOOL_RESULT = "tool_result";

    /**
     * 事件类型：本轮结束
     */
    private static final String EVENT_DONE = "done";

    /**
     * 事件类型：清空本轮已下发的自答正文（REQ-AI-31 指定专家兜底）。
     *
     * <p>入口轮在「已指定专家但模型未调用决策工具」时会整轮自答，服务端需**丢弃这段自答**
     * 并补执行强制编排 + 整合轮；前端据此清掉文本片段（保留工具/路由卡片），
     * 避免「模型自答 + 整合结果」被拼接显示。</p>
     */
    private static final String EVENT_ANSWER_RESET = "answer_reset";

    /**
     * 事件类型：本轮失败
     */
    private static final String EVENT_ERROR = "error";

    /**
     * 事件类型：工具调用需人工确认（HITL 暂停）
     */
    private static final String EVENT_TOOL_CONFIRM = "tool_confirm";

    /**
     * 事件类型：本轮执行被用户停止（收尾事件，等价于结束）
     */
    private static final String EVENT_ABORTED = "aborted";

    /**
     * 用户主动停止本轮的说明文案（落链路追踪 / 编排运行的中止原因）
     */
    private static final String CANCELLED_TEXT = "用户停止生成";

    /**
     * 专家链路暂停时落库的助手消息正文（REQ-AI-30 Phase 2 HITL 贯通）。
     *
     * <p>用平台固定文案而非入口智能体在暂停后可能续写的残句，避免用户看到半截回答。</p>
     */
    private static final String PENDING_EXPERT_TEXT =
        "专家准备执行的操作需要你确认后才会继续，请在下方确认卡片中处置。\n\n";

    /**
     * 整合轮开场指令：HITL 恢复段（专家在用户确认后续跑完毕）
     */
    private static final String INTEGRATION_INTRO_RESUME =
        "用户已确认执行。以下是专家返回的结论，请按整合纪律输出最终回答：\n\n";

    /**
     * 整合轮开场指令：指定专家兜底段（模型未调用决策工具，服务端补执行强制编排）
     */
    private static final String INTEGRATION_INTRO_PINNED =
        "本轮用户已指定专家。以下是该专家返回的结论，请按整合纪律输出最终回答：\n\n";

    /**
     * 指定专家的来源标记：用户指定（写入 {@code ai_route_run.decision_json} 便于复盘）
     */
    private static final String PIN_SOURCE_USER = "user";

    /**
     * 会话状态：进行中
     */
    private static final String SESSION_ACTIVE = "0";

    private final IAiSessionService sessionService;

    private final IAiAgentService agentService;

    private final AiMessageMapper messageMapper;

    private final AgentRegistry agentRegistry;

    private final AiProperties aiProperties;

    private final AiConfirmPendingStore pendingStore;

    private final AiPermissionModeStore permissionModeStore;

    private final IAiPermissionDecisionService decisionService;

    private final IAiUsageStatService usageStatService;

    private final IAiQuotaService quotaService;

    /**
     * 安全合规守卫（用户输入审核；输出侧在事件桥与收尾处）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * 安全事件留痕
     */
    private final IAiSafetyEventService safetyEventService;

    /**
     * 会话级资源集（REQ-AI-32）：实时对话链路每轮现读，小Z 与专家共用同一份
     */
    private final IAiSessionResourceService sessionResourceService;

    /**
     * 领域专家名录（REQ-AI-30）：入口智能体每轮现算候选专家清单
     */
    private final AiExpertDirectory expertDirectory;

    /**
     * 编排链路落库（REQ-AI-30）：一次编排一行 + 每次专家调用一行
     */
    private final AiRouteRecorder routeRecorder;

    /**
     * 专家编排器（REQ-AI-30）：无人值守链路的「指定专家兜底」直接同步调用（无 SSE）
     */
    private final AiRouteOrchestrator routeOrchestrator;

    /**
     * 结构化决策影子判定（docs/21，T5）：默认关闭（关闭态零外呼）；影子态只留痕、不接管编排
     */
    private final RouteJudgeService routeJudgeService;

    /**
     * 附件多模态装配（聊天上传图片/文档 → 内容块）
     */
    private final AiAttachmentAssembler attachmentAssembler;

    /**
     * 编排 / 运行期无状态辅助（技能白名单、场景包、来源、任务片段）
     */
    private final AiRouteSupport routeSupport;

    /**
     * 「记住该工具」用户级信任的落位（入口链路与专家链路）
     */
    private final AiTrustApplier trustApplier;

    /**
     * 继承槽位（REQ-AI-31）：每轮按「会话级 &gt; 人级 &gt; 平台默认」合并后注入入口智能体
     */
    private final AiSlotStore slotStore;

    /**
     * AgentScope 事件 → 流式状态与 SSE 信封的桥接器
     */
    private final AiStreamEventBridge streamBridge;

    /**
     * 链路追踪（REQ-AI-28）：记录执行期模型版本 / 上下文清单 / 状态迁移
     */
    private final IAiTraceService traceService;

    /**
     * 工具中文名与说明解析（HITL 确认卡片展示，带 TTL 缓存）
     */
    private final ToolLabelResolver toolLabelResolver;

    /**
     * 单次执行取消注册表：对话页「停止」与 SSE 断连据此取消后端执行（AgentScope 2.0.4）
     */
    private final AiRunRegistry runRegistry;

    /**
     * 收尾落库与留痕（R3 拆出）
     */
    private final AiTurnFinisher turnFinisher;

    /**
     * 整合轮与指定专家兜底的编排驱动（R4 拆出）
     */
    private final AiIntegrationDriver integrationDriver;

    /**
     * 编排链路的专家续跑驱动（R4 拆出）
     */
    private final AiExpertResumeDriver expertResumeDriver;

    /**
     * 停止该会话当前的流式执行（对话页「停止」按钮 / SSE 断连）。
     *
     * <p>取消经 {@link AiRunRegistry} 落到**后端**的 {@code AgentRun}：正在跑的模型调用与工具循环就地中止，
     * 收尾按「已中止」落库，避免出现「前端已停、后端仍在跑并继续落库」。</p>
     *
     * @param sessionId 会话主键
     */
    public void stop(Long sessionId) {
        sessionService.getOwnedSession(sessionId);
        if (!runRegistry.cancel(sessionId)) {
            log.debug("停止请求无活跃执行：sessionId={}", sessionId);
        }
    }

    /**
     * 发起一轮流式对话。
     *
     * @param sessionId     会话主键（即 AgentScope 的 sessionId）
     * @param userMessageId 已落库的用户消息主键
     * @param emitter       SSE 发射器
     */
    public void streamChat(Long sessionId, Long userMessageId, SseEmitter emitter) {
        long startNanos = System.nanoTime();
        AiSession session = sessionService.getOwnedSession(sessionId);
        if (!SESSION_ACTIVE.equals(session.getStatus())) {
            throw new ServiceException("会话已结束，无法继续提问");
        }
        AiMessage userMessage = messageMapper.selectById(userMessageId);
        if (userMessage == null || !sessionId.equals(userMessage.getSessionId())) {
            throw new ServiceException("消息不存在或不属于该会话");
        }
        AiAgent definition = agentService.getEnabledAgent(session.getAgentId());
        // 会话级资源集（REQ-AI-32）：每轮现读（含旧会话 scenario_pack_id 兼容读取），
        // 对入口智能体（自答 / 整合轮）与被执行专家同时可见；改选择后下一轮即按新集合装配生效
        AiSessionResourceSet resources = sessionResourceService.resolveForSession(session);
        Long userId = LoginHelper.getUserId();
        // 配额闸门：超限直接拒绝，避免产生「有提问无回答」的消耗
        quotaService.check(userId, definition);
        // 安全合规（输入）：命中即留痕；BLOCK 直接回拒答、不调模型（避免「有提问无回答」的被动消耗）
        SafetyDecision inputGuard = safetyGuard.guard(userMessage.getContent(), SafetyScene.INPUT);
        if (inputGuard.hit()) {
            safetyEventService.record(SafetyScene.INPUT, inputGuard, safetyGuard.engineName(),
                sessionId, userMessageId, definition.getId(), userId, userMessage.getContent());
        }
        if (inputGuard.blocked()) {
            sendRefusal(emitter, sessionId, userId, startNanos);
            return;
        }
        // 过审后的用户内容（MASK 时为掩码文本）才是下发给模型与专家的口径
        String userContent = inputGuard.text();
        // 装配一次同时取「实例 + 执行期快照」：快照供链路追踪挂父链路，实例供本轮执行。
        // 原先 snapshotOf 与下方的 get 各走一遍完整装配（各含绑定 / 工具 / 知识库 / 技能 / 子智能体 /
        // 运行期策略 / MCP 解析等查询），合并后每轮省掉一遍（口径见 docs/22 §3.1 C「前提修正」）
        AgentRegistry.AgentHolder holder = agentRegistry.holder(definition, resources);
        // 链路追踪（REQ-AI-28）先建：编排运行状态需要它以建立「父链路 → 专家子链路」的关系
        Long traceId = traceService.start(sessionId, definition.getId(), userId, AiTrace.SOURCE_CHAT,
            holder.snapshot());
        // 编排分支（REQ-AI-30）：入口智能体（{@code ai_entry_agent}）的一轮对话即一次编排运行。
        // 候选清单在此**现算**（提问用户可见 ∩ 启用 ∩ 自动路由），故新增/停用专家无需重启或重新装配。
        // 用三目一次性赋值：变量需「有效 final」才能被下方的订阅回调捕获。
        boolean entryAgent = definition.isEntry();
        Long deptId = entryAgent ? LoginHelper.getDeptId() : null;
        // 继承槽位（REQ-AI-31）：仅入口智能体需要——注入小Z 的 system prompt，并随 task_context 传给专家。
        // 合并顺序「平台默认 → 人级 → 会话级」，读 Redis 发生在请求线程（不占 Reactor 线程）
        AiSlotSet slots = entryAgent ? slotStore.merge(userId, sessionId) : null;
        // 聊天附件（聊天上传图片/文档）：文档解析文本内联、图片转 Base64 内容块；
        // 入口轮与专家轮共用同一批内容块（专家用独立会话、拿不到用户历史，必须随任务片段一并下发）
        List<ContentBlock> attachmentBlocks = attachmentAssembler.buildBlocks(userMessage);
        AiRouteContext route = entryAgent
            ? new AiRouteContext(routeRecorder.startRun(sessionId, definition.getId(), userId),
                sessionId, userId, deptId, definition.getId(),
                expertDirectory.candidates(userId, deptId), traceId, emitter)
            : null;
        if (route != null) {
            // 会话级资源集（REQ-AI-32）：专家继承同一份，供其装配出与小Z 同源的工具 / 技能 / MCP 工具可见面
            route.resources = resources;
            // 附件内容块随编排上下文下发给专家（REQ：聊天上传图片/文档）
            route.attachmentBlocks = attachmentBlocks;
            // 指定专家（固化路由，REQ-AI-31）：会话上记「指定专家」→ 本轮强制路由到它并落 action=pinned
            route.pinnedExpertId = session.getPinnedExpertId();
            route.pinSource = PIN_SOURCE_USER;
            if (route.pinnedExpertId != null) {
                // 指定专家可能 auto_route='0'（不在自动候选清单内），按「可见专家」单独解析其候选视图（回归 B2）
                route.pinnedExpert = expertDirectory.visibleCandidate(userId, deptId, route.pinnedExpertId);
                // task_context 取**用户原话 + 槽位**（P7：不做额外的任务提炼调用），模型无权改写
                route.taskContext = AiRouteSupport.taskContext(userContent, slots, userId, deptId);
            }
        }
        HarnessAgent agent = holder.agent();
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
            .userId(String.valueOf(userId))
            .sessionId(String.valueOf(sessionId))
            // 技能生效范围（REQ-AI-10）：按提问用户覆盖技能白名单（绑定 ∪ 资源集 ∩ 启用 ∩ 可见）
            .put(SkillFilter.class, routeSupport.runtimeSkillFilter(definition.getId(), resources, userId))
            // 子智能体（REQ-AI-04）：强制同步调用。本平台是「一问一答、回答即结束」的会话模型，
            // 后台子任务既不会在本轮内完成，其「反向通知」也只会在下一轮提问时才注入，
            // 会出现「上一轮的结果出现在下一轮」的错位，故统一强制同步；并给出硬超时上限
            // （覆盖模型自选的 timeout_seconds，避免一次委派长时间拖住整个回答）
            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, AgentRegistry.SUBAGENT_SYNC_TIMEOUT_SECONDS);
        if (route != null) {
            // 编排上下文只在这一轮存在：工具据此判定「是否处于编排链路」
            contextBuilder.put(AiRouteContext.class, route);
        }
        if (slots != null) {
            // 继承槽位（REQ-AI-31）：由 SlotInheritanceMiddleware 每轮渲染进入口智能体的 system prompt
            contextBuilder.put(AiSlotSet.class, slots);
        }
        RuntimeContext ctx = contextBuilder.build();
        // 全局权限模式为运行期可变配置，共享单例必须在每轮对话前按 RuntimeContext 应用
        agent.setPermissionMode(ctx, permissionModeStore.resolve());
        // 「记住该工具」是用户级信任：每轮按提问用户把信任并入该会话的权限上下文（见方法注释）
        trustApplier.apply(agent, definition, resources, userId, sessionId);

        AiStreamState state = new AiStreamState();
        state.sessionId = sessionId;
        state.userId = userId;
        state.agentId = definition.getId();
        state.modelId = definition.getModelId();
        // 链路追踪（REQ-AI-28）：复用上面已建的链路（编排运行与它是同一条父链路）
        state.traceId = traceId;
        if (route != null) {
            // 编排链路的 HITL 贯通（REQ-AI-30 Phase 2）：专家命中「需确认」时，
            // 编排器需要把专家侧的待确认信息**上浮**写入入口轮状态，故此处回挂引用
            route.entryState = state;
        }
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_START));
            // SSE 断连 / 超时：取消该会话**当前全部**后端执行（与对话页「停止」同一路径）。
            // 统一在此登记一次——SseEmitter 的回调是单槽覆盖，分支内再注册会顶掉这里；
            // 故入口轮、短路轮、指定专家兜底、整合轮与恢复段共用这一套（原先只有入口轮分支注册过）
            emitter.onCompletion(() -> runRegistry.cancel(sessionId));
            emitter.onTimeout(() -> runRegistry.cancel(sessionId));
            emitter.onError(ignored -> runRegistry.cancel(sessionId));
            // 结构化决策闸门前置判定（docs/21 §5.4 / §5.7，T9）：在入口轮 ReAct **之前**判定，高置信且
            // 选项合法即采信并**短路到专家直答**（不再跑入口轮）。pinned 已指定专家，按口径跳过判定；
            // 关闭 / 非 gate 档 / 未采信时 takeOver=false，照常跑入口轮，行为与现状逐项等价。
            if (route != null && route.pinnedExpertId == null) {
                RouteJudgeService.JudgeGateOutcome gate =
                    routeJudgeService.judgeGate(route, userContent, userId, deptId, userMessageId);
                if (gate.takeOver()) {
                    // agent / ctx / session 一并传入：短路路径没有入口轮，专家未接管正文时需用它们补跑整合轮
                    runJudgeTakeover(emitter, sessionId, state, route, slots, userContent, deptId, userId,
                        gate.expertCode(), startNanos, agent, ctx, session);
                    return;
                }
            }
            // 单次执行句柄（AgentScope 2.0.4）：登记后可由「停止」/ SSE 断连取消本段后端执行
            AgentRun<AgentEvent> run = agent.prepareRun(
                List.of(attachmentAssembler.buildUserMsg(userContent, attachmentBlocks)), ctx);
            AiRunRegistry.Cancelable cancelable = run::cancel;
            runRegistry.register(sessionId, cancelable);
            Disposable disposable = run.stream()
                .doFinally(signal -> runRegistry.unregister(sessionId, cancelable))
                .subscribe(
                event -> dispatchEntryEvent(emitter, state, route, event),
                error -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    // 用户停止 / SSE 断连触发的取消：按「已中止」收尾，不当作失败
                    if (run.status() == AgentRun.Status.CANCELLED) {
                        turnFinisher.finishCancelled(emitter, sessionId, state, durationMs, null, route);
                        return;
                    }
                    log.error("对话流式执行失败，sessionId={}", sessionId, error);
                    String message = AiSseSupport.rootCauseMessage(error);
                    traceService.appendTransition(state.traceId, "error", message);
                    traceService.accumulate(state.traceId, AiTrace.STATUS_FAILED, durationMs, null,
                        null, null, null, message);
                    try {
                        sessionService.persistAssistantFailure(sessionId, message, durationMs);
                    } catch (Exception e) {
                        log.error("落库失败消息异常，sessionId={}", sessionId, e);
                    }
                    turnFinisher.failStream(emitter, message, error);
                    // 编排运行收尾：异常也算终态，便于事后从 ai_route_run 定位本次编排
                    turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
                },
                () -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    if (state.pendingConfirm) {
                        // 暂停也要回填本轮的助手消息主键：否则管理面「编排运行」里这条 pending 记录
                        // 既看不到入口轮 Token 也跳不到消息（REQ-AI-30 Phase 2 第三项）
                        Long pausedMessageId = turnFinisher.finishPending(emitter, sessionId, state, durationMs, route);
                        turnFinisher.finishRoute(route, pausedMessageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
                        return;
                    }
                    // 指定专家兜底（REQ-AI-31）：pin 存在但模型整轮未调用决策工具（直接自答）
                    // → 服务端补执行一次强制编排 + 整合轮，保证「指定了就一定由它答」
                    if (route != null && route.pinnedExpertId != null && !route.routeInvoked) {
                        integrationDriver.drivePinnedFallback(agent, ctx, session, route, state, startNanos, emitter);
                        return;
                    }
                    AiMessage assistant = turnFinisher.finish(emitter, sessionId, state, durationMs, null, route);
                    // 结构化决策影子判定（docs/21 §5.7，T5）：放在 finish 之后——用户答案与 done 已下发，
                    // 判定外呼既不拖慢用户可见耗时，又能在紧随其后的 finishRoute 同一次落库更新里带上审计字段。
                    // 默认关闭（零外呼）；pinned 已指定专家，按口径跳过判定；结果只写审计，不改编排走向。
                    // 闸门档在**入口轮之前**已做过前置判定（judgeMode='G'），此处不再重复判定，避免同一轮双跑。
                    if (route != null && route.pinnedExpertId == null
                        && !RouteJudgeService.MODE_CODE_GATE.equals(route.judgeMode)) {
                        routeJudgeService.judgeShadow(route, userContent, userId, deptId, userMessageId);
                    }
                    turnFinisher.finishRoute(route, assistant == null ? null : assistant.getId(),
                        route != null && route.degraded ? AiRouteRun.STATUS_DEGRADED : AiRouteRun.STATUS_SUCCESS,
                        null, durationMs);
                }
            );
        } catch (Exception e) {
            throw e instanceof ServiceException serviceException
                ? serviceException : new ServiceException("对话启动失败：" + e.getMessage());
        }
    }

    /**
     * 闸门采信后的**专家直答短路收尾**（docs/21 §5.7，T9）。
     *
     * <p>形态照抄入口轮收尾的**非 pinned 分支**，但没有入口轮：直接调 {@link AiRouteOrchestrator#execute}
     * 让专家把回答**直接交付用户**（REQ-AI-30 专家直答口径），随后按同款收尾落库。
     * <b>刻意不调用</b> {@link AiIntegrationDriver#drivePinnedFallback}——它是「服务端强制编排 +
     * 小Z 整合轮」的旧形态，与本任务的「专家直答」口径不符。</p>
     *
     * <p><b>专家未接管正文的兜底</b>：短路路径没有入口轮，若专家失败 / 超时 / 空正文
     * （编排器把 {@code handoff} 置回 false 且 {@code degraded=true}），照常收尾会取不到正文而让用户
     * 收到空回答。故此时**只复用** {@link AiIntegrationDriver#driveIntegration} 这一步补跑整合轮，
     * 让小Z 基于降级报告产出兜底回答（整合轮内部自带收尾，本方法该分支不再重复收尾）。</p>
     *
     * <p>本方法在 servlet 请求线程同步执行（同 {@code executeUnattended} 的阻塞口径），
     * 故专家编排的阻塞等待无需切换调度线程。</p>
     *
     * @param emitter     SSE 发射器
     * @param sessionId   会话主键
     * @param state       入口轮流式累积状态（未跑入口轮，正文由专家直答承担）
     * @param route       编排运行状态
     * @param slots       继承槽位（构造 task_context 用）
     * @param userContent 本轮用户原话
     * @param deptId      提问用户部门
     * @param userId      提问用户
     * @param expertCode  判定选中的候选专家 {@code agent_code}
     * @param startNanos  本轮起始纳秒（计耗时）
     * @param agent       入口智能体（已装配；专家未接管时供整合轮兜底作答，不重新装配）
     * @param ctx         入口智能体的运行期上下文（已带编排上下文，整合轮不再允许路由）
     * @param session     会话（整合轮经 {@link AiIntegrationDriver#driveIntegration} 取会话主键）
     */
    private void runJudgeTakeover(SseEmitter emitter, Long sessionId, AiStreamState state, AiRouteContext route,
                                  AiSlotSet slots, String userContent, Long deptId, Long userId,
                                  String expertCode, long startNanos, HarnessAgent agent, RuntimeContext ctx,
                                  AiSession session) {
        AiExpertCandidate candidate = route.candidateByKey.get(expertCode);
        if (candidate == null) {
            // 防御性：前置已校验 choice 命中候选，此处兜底按自答收尾，避免把整轮打挂
            log.warn("闸门采信但候选缺失，按自答兜底收尾：sessionId={}, expertCode={}", sessionId, expertCode);
            long fallbackMs = (System.nanoTime() - startNanos) / 1_000_000;
            route.action = AiRouteRun.ACTION_SELF;
            AiMessage fallback = turnFinisher.finish(emitter, sessionId, state, fallbackMs, null, route);
            turnFinisher.finishRoute(route, fallback == null ? null : fallback.getId(),
                AiRouteRun.STATUS_SUCCESS, null, fallbackMs);
            return;
        }
        // 编排动作与任务片段由服务端设置：task_context 取「用户原话 + 槽位」模板（与 pinned 同源，P7）
        route.action = AiRouteRun.ACTION_ROUTE;
        route.taskContext = AiRouteSupport.taskContext(userContent, slots, userId, deptId);
        // 语义：路由已发生，避免收尾的兜底分支再次发起编排
        route.routeInvoked = true;
        route.decisionJson = judgeDecisionJson(route, expertCode);
        String report;
        try {
            report = routeOrchestrator.execute(route, candidate);
        } catch (Exception e) {
            // 专家编排异常不得把 SSE 线程打挂：按失败收尾（同 onError 分支），异常不外抛
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            String message = AiSseSupport.rootCauseMessage(e);
            log.error("闸门采信后专家编排失败，sessionId={}", sessionId, e);
            traceService.appendTransition(state.traceId, "error", message);
            turnFinisher.failStream(emitter, message, e);
            turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            return;
        }
        log.info("闸门采信短路：sessionId={}, expert={}, report={}", sessionId, expertCode,
            AiSseSupport.abbreviate(report, 200));
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (route.cancelled) {
            // 用户停止（「停止生成」/ SSE 断连）：专家调用已被中断（编排器置 route.cancelled），
            // 按「已中止」收尾；**不**补跑降级整合轮——用户已经不想看了，再跑一整轮只会继续产生副作用
            turnFinisher.finishCancelled(emitter, sessionId, state, durationMs, null, route);
            return;
        }
        if (state.pendingConfirm) {
            // 专家命中「需确认」：暂停并落确认卡片，用户确认后由恢复段续跑（与入口轮同款）
            Long pausedMessageId = turnFinisher.finishPending(emitter, sessionId, state, durationMs, route);
            turnFinisher.finishRoute(route, pausedMessageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
            return;
        }
        if (!route.handoff) {
            // 专家**未接管正文**（失败 / 超时 / 空正文：编排器已把 handoff 置回 false 且 degraded=true）。
            // 短路路径没有入口轮可续写，若照常 finish 会取不到正文 → 用户收到空回答。
            // 故补一次整合轮，复用既有整合轮驱动让小Z 基于降级报告产出兜底回答；
            // messageId 传 null（本轮尚未落库助手消息，故无可追加/可清理的暂存）。
            // driveIntegration 内部自带收尾（finish / failStream + finishRoute，取消走 finishCancelled），
            // 故此分支不再重复收尾，避免双重落库与重复 done。
            integrationDriver.driveIntegration(agent, ctx, session, null, route, state, report,
                AiIntegrationDriver.INTEGRATION_INTRO_JUDGE_DEGRADED, startNanos, emitter);
            return;
        }
        AiMessage assistant = turnFinisher.finish(emitter, sessionId, state, durationMs, null, route);
        turnFinisher.finishRoute(route, assistant == null ? null : assistant.getId(),
            route.degraded ? AiRouteRun.STATUS_DEGRADED : AiRouteRun.STATUS_SUCCESS, null, durationMs);
    }

    /**
     * 闸门采信段的决策留档（服务端构造，无模型决策原文）：便于事后复盘「以何阈值、选了谁」。
     *
     * <p>不含密钥、不含 {@code state}；含来源 / 动作 / 选中专家 / 判定选项与置信度 / 阈值 / 模式。</p>
     *
     * @param route      编排运行状态（判定字段已写入）
     * @param expertCode 判定选中的候选专家 {@code agent_code}
     * @return 决策原文（JSON）
     */
    private String judgeDecisionJson(AiRouteContext route, String expertCode) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("source", "judge");
        decision.put("action", AiRouteRun.ACTION_ROUTE);
        decision.put("expert_agent_key", expertCode);
        decision.put("judge_choice", route.judgeChoice);
        decision.put("judge_confidence", route.judgeConfidence);
        decision.put("threshold", route.judgeThreshold);
        decision.put("mode", route.judgeMode);
        return JsonUtils.toJsonString(decision);
    }

    /**
     * 入口轮事件分派（专家直答修订，REQ-AI-30，2026-10-03）。
     *
     * <p>普通轮次直接交给事件桥；但一旦编排进入**专家直答接管态**（{@code route.handoff}），
     * 入口智能体（小Z）的**正文增量一律丢弃**——最终回答由专家正文承担，避免「小Z 的过渡语 /
     * 归纳」与专家回答拼接。工具 / 路由 / 确认等非正文事件照常处理（卡片与留痕不受影响）。</p>
     *
     * @param emitter SSE 发射器
     * @param state   入口轮流式状态
     * @param route   编排运行状态（非入口智能体为 null）
     * @param event   AgentScope 事件
     */
    private void dispatchEntryEvent(SseEmitter emitter, AiStreamState state, AiRouteContext route,
                                    AgentEvent event) {
        if (route != null && route.handoff && event instanceof TextBlockDeltaEvent) {
            return;
        }
        streamBridge.handle(emitter, state, event);
    }

    /**
     * 输入命中安全阻断：落库拒答助手消息并下发 start / text_delta / done，不调用模型。
     *
     * @param emitter     SSE 发射器
     * @param sessionId   会话主键
     * @param userId      提问用户
     * @param startNanos  本轮起始纳秒（计耗时）
     */
    private void sendRefusal(SseEmitter emitter, Long sessionId, Long userId, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        try {
            sessionService.persistAssistantTurn(sessionId, AiSafetyGuard.REFUSAL_TEXT, null, durationMs,
                List.of(), null, userId, List.of());
        } catch (Exception e) {
            log.error("落库安全拒答消息异常，sessionId={}", sessionId, e);
        }
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_START));
        Map<String, Object> payload = AiSseSupport.envelope("text_delta");
        payload.put("delta", AiSafetyGuard.REFUSAL_TEXT);
        AiSseSupport.send(emitter, payload);
        AiSseSupport.complete(emitter);
    }

    /**
     * 无人值守执行一轮对话（定时任务 / 渠道接入链路）。
     *
     * <p>与对话工作台的区别：无登录上下文（运行身份由调用方显式传入）、无 SSE 输出
     * （同步等待事件流结束）、权限模式固定为 {@link PermissionMode#DONT_ASK}
     * ——被配置为「需确认」的写工具会被框架直接拒绝，既不执行也不会悬挂等待人工确认。</p>
     *
     * <p><b>编排（全场景可用）</b>：当会话绑定的智能体是**入口智能体（小Z）**时，本轮同样建立
     * {@link AiRouteContext}（{@code emitter=null}）接入「入口选人 + 专家直答」编排——专家侧一律
     * 拒绝「需确认」写工具（无人可确认），失败即降级为小Z 兜底自答。绑定非入口智能体时保持原
     * 单智能体行为。</p>
     *
     * @param session          已创建的会话（归属为运行身份用户）
     * @param userMessage      已落库的用户消息（任务提示词）
     * @param runAsUserId      运行身份用户
     * @return 执行结果
     */
    public AiTaskExecution executeUnattended(AiSession session, AiMessage userMessage, Long runAsUserId) {
        return executeUnattended(session, userMessage, runAsUserId, List.of());
    }

    /**
     * 无人值守执行一轮对话（含入站附件内容块，供渠道多媒体使用）。
     *
     * @param session          已创建的会话（归属为运行身份用户）
     * @param userMessage      已落库的用户消息（任务提示词；正文仍落库，附件内容块只在本轮下发模型）
     * @param runAsUserId      运行身份用户
     * @param attachmentBlocks 入站附件内容块（图片 / 文档；无附件时传空列表）
     * @return 执行结果
     */
    public AiTaskExecution executeUnattended(AiSession session, AiMessage userMessage, Long runAsUserId,
                                             List<ContentBlock> attachmentBlocks) {
        long startNanos = System.nanoTime();
        Long sessionId = session.getId();
        AiAgent definition = agentService.getEnabledAgent(session.getAgentId());
        // 无人值守会话（定时任务 / 渠道接入）默认未选场景包，故不接入任何 MCP 工具
        AiScenarioPack pack = routeSupport.resolveScenarioPack(session);
        // 配额闸门与实时对话同口径：超限直接失败，原因写入执行记录
        quotaService.check(runAsUserId, definition);
        // 入口智能体（小Z）：本轮即一次编排运行——按会话资源集装配（与实时对话同源）
        boolean entryAgent = definition.isEntry();
        AiSessionResourceSet resources = entryAgent
            ? sessionResourceService.resolveForSession(session) : AiSessionResourceSet.empty();
        Long deptId = session.getDeptId();
        HarnessAgent agent = entryAgent
            ? agentRegistry.get(definition, resources) : agentRegistry.get(definition, pack);
        // 继承槽位（REQ-AI-31）：仅入口智能体需要（注入小Z 的 system prompt 并随 task_context 传给专家）
        AiSlotSet slots = entryAgent ? slotStore.merge(runAsUserId, sessionId) : null;
        // 链路追踪（REQ-AI-28）：无人值守链路，来源按会话来源标记（定时任务 / 渠道接入）
        Long traceId = traceService.start(sessionId, definition.getId(), runAsUserId,
            AiRouteSupport.resolveTraceSource(session), entryAgent
                ? agentRegistry.snapshotOf(definition, resources) : agentRegistry.snapshotOf(definition, pack));
        AiRouteContext route = entryAgent
            ? new AiRouteContext(routeRecorder.startRun(sessionId, definition.getId(), runAsUserId),
                sessionId, runAsUserId, deptId, definition.getId(),
                expertDirectory.candidates(runAsUserId, deptId), traceId, null)
            : null;
        if (route != null) {
            // 无人值守编排：无人类可确认，专家侧一律拒绝写工具（见 AiRouteOrchestrator#applyExpertPermission）
            route.unattended = true;
            route.resources = resources;
            route.attachmentBlocks = attachmentBlocks == null ? List.of() : attachmentBlocks;
            route.pinnedExpertId = session.getPinnedExpertId();
            route.pinSource = PIN_SOURCE_USER;
            if (route.pinnedExpertId != null) {
                route.pinnedExpert = expertDirectory.visibleCandidate(runAsUserId, deptId, route.pinnedExpertId);
                route.taskContext = AiRouteSupport.taskContext(userMessage.getContent(), slots, runAsUserId, deptId);
            }
        }
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
            .userId(String.valueOf(runAsUserId))
            .sessionId(String.valueOf(sessionId))
            // 技能生效范围（REQ-AI-10）：无人值守链路按运行身份用户过滤
            .put(SkillFilter.class, entryAgent
                ? routeSupport.runtimeSkillFilter(definition.getId(), resources, runAsUserId)
                : routeSupport.runtimeSkillFilter(definition.getId(), pack, runAsUserId))
            // 子智能体（REQ-AI-04）：无人值守链路必须强制同步，否则后台子任务会在本次执行结束前
            // 就被遗弃（无人追问、无人等待），结果永远无法回收；同样给出硬超时上限
            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, AgentRegistry.SUBAGENT_SYNC_TIMEOUT_SECONDS);
        if (route != null) {
            // 编排上下文只在这一轮存在：工具据此判定「是否处于编排链路」
            contextBuilder.put(AiRouteContext.class, route);
        }
        if (slots != null) {
            contextBuilder.put(AiSlotSet.class, slots);
        }
        RuntimeContext ctx = contextBuilder.build();
        // 无人值守：权限模式恒为 DONT_ASK，并把「需确认」的写工具按会话降级为「拒绝」，不执行、不悬挂
        agent.setPermissionMode(ctx, PermissionMode.DONT_ASK);
        AiPermissionSupport.denyAskingTools(agent, String.valueOf(runAsUserId), String.valueOf(sessionId));

        AiStreamState state = new AiStreamState();
        state.sessionId = sessionId;
        state.userId = runAsUserId;
        state.agentId = definition.getId();
        state.modelId = definition.getModelId();
        state.traceId = traceId;
        if (route != null) {
            route.entryState = state;
        }
        List<ContentBlock> blocks = route != null ? route.attachmentBlocks
            : (attachmentBlocks == null ? List.of() : attachmentBlocks);
        try {
            Flux<AgentEvent> stream = agent.streamEvents(
                    attachmentAssembler.buildUserMsg(userMessage.getContent(), blocks), ctx)
                .doOnNext(event -> streamBridge.handle(null, state, event));
            long timeoutMs = aiProperties.getChat().getTaskTimeoutMs();
            if (timeoutMs > 0) {
                stream.blockLast(Duration.ofMillis(timeoutMs));
            } else {
                // 与 SSE 口径一致：配置为 0 表示不超时
                stream.blockLast();
            }
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("无人值守执行失败，sessionId={}", sessionId, e);
            String message = AiSseSupport.rootCauseMessage(e);
            traceService.appendTransition(state.traceId, "error", message);
            traceService.accumulate(state.traceId, AiTrace.STATUS_FAILED, durationMs, null,
                null, null, null, message);
            try {
                sessionService.persistAssistantFailure(sessionId, message, durationMs);
            } catch (Exception ex) {
                log.error("落库失败消息异常，sessionId={}", sessionId, ex);
            }
            turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            return new AiTaskExecution(sessionId, null, false, message, durationMs, 0, null, null, null);
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        // 无人值守不可能人工确认：若仍出现待确认暂停（理论上被 DONT_ASK 阻断）按失败收尾，绝不悬挂
        if (route != null && route.pendingResume != null) {
            String message = "该操作需要人工确认，无人值守链路无法继续（已拒绝执行）";
            log.warn("无人值守链路出现待确认暂停，按失败收尾：sessionId={}", sessionId);
            traceService.appendTransition(state.traceId, "pending_confirm", message);
            try {
                sessionService.persistAssistantFailure(sessionId, message, durationMs);
            } catch (Exception ex) {
                log.error("落库失败消息异常，sessionId={}", sessionId, ex);
            }
            turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            return new AiTaskExecution(sessionId, null, false, message, durationMs, 0, null, null, null);
        }
        // 指定专家兜底（REQ-AI-31）：pin 存在但模型整轮未调用决策工具 → 服务端补执行一次强制编排。
        // 无人值守为同步阻塞：专家成功则正文直答；失败时 handoff 被置回 false，落小Z 自身正文
        if (route != null && route.pinnedExpertId != null && !route.routeInvoked) {
            route.action = AiRouteRun.ACTION_PINNED;
            route.decisionJson = pinnedFallbackDecision(route);
            try {
                routeOrchestrator.executePinned(route);
            } catch (Exception e) {
                route.degraded = true;
                route.degradeReason = AiSseSupport.rootCauseMessage(e);
                log.warn("无人值守指定专家兜底失败，sessionId={}：{}", sessionId, route.degradeReason);
            }
        }
        AiMessage assistant = turnFinisher.finish(null, sessionId, state, durationMs, null, route);
        if (assistant == null) {
            return new AiTaskExecution(sessionId, null, false, "回答已生成但保存失败", durationMs, 0, null, null, null);
        }
        turnFinisher.finishRoute(route, assistant.getId(),
            route != null && route.degraded ? AiRouteRun.STATUS_DEGRADED : AiRouteRun.STATUS_SUCCESS, null, durationMs);
        return new AiTaskExecution(sessionId, assistant.getId(), true, null, durationMs,
            assistant.getToolCallCount(), assistant.getPromptTokens(), assistant.getCompletionTokens(),
            assistant.getTotalTokens());
    }

    /**
     * 无人值守链路「指定专家兜底」段的决策留档（无模型决策，记一条服务端补执行的说明）。
     *
     * @param route 编排运行状态（{@code pinnedExpertId} 非空）
     * @return 决策原文（JSON）
     */
    private String pinnedFallbackDecision(AiRouteContext route) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", AiRouteRun.ACTION_PINNED);
        decision.put("pin_source", route.pinSource);
        decision.put("expert_agent_id", route.pinnedExpertId);
        decision.put("fallback", true);
        decision.put("note", "无人值守链路模型未调用 route_decision，服务端补执行强制编排");
        return JsonUtils.toJsonString(decision);
    }

    /**
     * 恢复被 HITL 暂停的一轮对话（用户已在确认接口写入处置结果）。
     *
     * @param sessionId 会话主键
     * @param messageId 暂停时落库的助手消息主键
     * @param emitter   SSE 发射器
     */
    public void streamResume(Long sessionId, Long messageId, SseEmitter emitter) {
        long startNanos = System.nanoTime();
        AiSession session = sessionService.getOwnedSession(sessionId);
        AiMessage existMessage = messageMapper.selectById(messageId);
        if (existMessage == null || !sessionId.equals(existMessage.getSessionId())) {
            throw new ServiceException("待确认消息不存在或不属于该会话");
        }
        AiConfirmPendingStore.PendingConfirm pending = pendingStore.get(sessionId, messageId)
            .orElseThrow(() -> new ServiceException("确认已过期或已被处理，请重新提问"));
        if (pending.decisions() == null || pending.decisions().isEmpty()) {
            throw new ServiceException("尚未收到确认结果");
        }
        if (pending.expertResume() != null) {
            // 专家链路的暂停：恢复段必须驱动**专家**续跑，再由入口智能体整合（REQ-AI-30 Phase 2 HITL 贯通）
            expertResumeDriver.resumeRoute(session, messageId, pending, emitter);
            return;
        }
        AiAgent definition = agentService.getEnabledAgent(session.getAgentId());
        // 恢复段与首段使用同一份会话资源集（REQ-AI-32：每轮现读，会话内可变），保证工具 / 技能与首段一致
        AiSessionResourceSet resources = sessionResourceService.resolveForSession(session);
        HarnessAgent agent = agentRegistry.get(definition, resources);
        RuntimeContext ctx = RuntimeContext.builder()
            .userId(String.valueOf(pending.userId()))
            .sessionId(String.valueOf(sessionId))
            // 技能生效范围（REQ-AI-10）：恢复段与首段保持同一份白名单
            .put(SkillFilter.class, routeSupport.runtimeSkillFilter(definition.getId(), resources, pending.userId()))
            // 继承槽位（REQ-AI-31）：恢复段与首段保持同一份槽位
            .put(AiSlotSet.class, slotStore.merge(pending.userId(), sessionId))
            .build();
        agent.setPermissionMode(ctx, permissionModeStore.resolve());
        // 恢复段同样按提问用户同步信任（首段已同步时此处为空操作）
        trustApplier.apply(agent, definition, resources, pending.userId(), sessionId);

        AiStreamState state = new AiStreamState();
        state.sessionId = sessionId;
        state.userId = pending.userId();
        state.agentId = pending.agentId();
        state.modelId = definition.getModelId();
        // 链路追踪（REQ-AI-28）：复用暂停阶段那条链路，并追加恢复节点
        state.traceId = traceService.findIdByMessage(sessionId, messageId);
        if (state.traceId == null) {
            state.traceId = traceService.start(sessionId, definition.getId(), pending.userId(), AiTrace.SOURCE_CHAT,
                agentRegistry.snapshotOf(definition, resources));
        } else {
            traceService.appendTransition(state.traceId, "resume", "人工确认后恢复执行");
        }
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_START));
            // SSE 断连 / 超时：取消该会话**当前全部**后端执行（与对话页「停止」同一路径）。
            // 恢复段是**另一条 SSE 请求**，故此处同样要登记（与 streamChat 同款；SseEmitter 回调单槽覆盖）
            emitter.onCompletion(() -> runRegistry.cancel(sessionId));
            emitter.onTimeout(() -> runRegistry.cancel(sessionId));
            emitter.onError(ignored -> runRegistry.cancel(sessionId));
            AgentRun<AgentEvent> run = agent.prepareRun(AiResumeMessageFactory.build(pending), ctx);
            AiRunRegistry.Cancelable cancelable = run::cancel;
            runRegistry.register(sessionId, cancelable);
            Disposable disposable = run.stream()
                .doFinally(signal -> runRegistry.unregister(sessionId, cancelable))
                .subscribe(
                event -> streamBridge.handle(emitter, state, event),
                error -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    // 用户停止 / SSE 断连触发的取消：按「已中止」收尾，不当作失败
                    if (run.status() == AgentRun.Status.CANCELLED) {
                        pendingStore.remove(sessionId, messageId);
                        turnFinisher.finishCancelled(emitter, sessionId, state, durationMs, messageId, null);
                        return;
                    }
                    log.error("HITL 恢复执行失败，sessionId={}, messageId={}", sessionId, messageId, error);
                    String message = AiSseSupport.rootCauseMessage(error);
                    traceService.appendTransition(state.traceId, "error", "恢复失败：" + message);
                    traceService.accumulate(state.traceId, AiTrace.STATUS_ABORTED, durationMs, null,
                        null, null, null, message);
                    try {
                        sessionService.appendAssistantTurn(messageId, "\n\n" + message,
                            AiMessage.STATUS_ABORTED, durationMs, null, state.toTokenUsage(), state.sources);
                        usageStatService.accumulate(state.userId, state.agentId, state.modelId,
                            state.toTokenUsage(), 0, 0);
                    } catch (Exception e) {
                        log.error("落库恢复失败消息异常，messageId={}", messageId, e);
                    }
                    pendingStore.remove(sessionId, messageId);
                    turnFinisher.failStream(emitter, message, error);
                },
                () -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    if (state.pendingConfirm) {
                        // 恢复段内再次命中「需确认」工具（一次提问包含多次写操作）：继续暂停，
                        // 覆盖暂存并再次下发确认卡片；暂存**不能清除**，否则用户无法处置第二次确认
                        turnFinisher.finishPendingResume(emitter, sessionId, state, durationMs, messageId, null);
                        return;
                    }
                    turnFinisher.finish(emitter, sessionId, state, durationMs, messageId, null);
                    pendingStore.remove(sessionId, messageId);
                }
            );
        } catch (Exception e) {
            throw e instanceof ServiceException serviceException
                ? serviceException : new ServiceException("恢复执行失败：" + e.getMessage());
        }
    }

    /**
     * 记录用户对待确认工具调用的处置结果（确认 / 拒绝 / 记住）。
     *
     * <p>处置结果写入 Redis 暂存，随后由 {@link #streamResume(Long, Long, SseEmitter)} 读取并恢复执行。</p>
     *
     * @param sessionId 会话主键
     * @param bo        确认参数
     */
    public void confirm(Long sessionId, AiChatConfirmBo bo) {
        sessionService.getOwnedSession(sessionId);
        AiConfirmPendingStore.PendingConfirm pending = pendingStore.get(sessionId, bo.getMessageId())
            .orElseThrow(() -> new ServiceException("确认已过期或已被处理，请重新提问"));
        boolean confirmed = Boolean.TRUE.equals(bo.getConfirmed());
        boolean remember = Boolean.TRUE.equals(bo.getRemember());
        // 自定义拒绝原因（可选）：仅在拒绝时生效，回传模型并写入决策日志
        String rejectReason = StringUtils.trim(bo.getRejectReason());
        List<AiConfirmPendingStore.PendingDecision> decisions = new ArrayList<>();
        for (AiConfirmPendingStore.PendingToolCall call : pending.toolCalls()) {
            decisions.add(new AiConfirmPendingStore.PendingDecision(call.toolCallId(), confirmed, remember, rejectReason));
        }
        pendingStore.save(new AiConfirmPendingStore.PendingConfirm(pending.sessionId(), pending.messageId(),
            pending.agentId(), pending.userId(), pending.replyId(), pending.toolCalls(), decisions,
            pending.createdAt(), pending.expertResume()));
        decisionService.applyConfirm(bo.getMessageId(), confirmed, remember, rejectReason);
        // 框架原生 HITL：确认结果通过 ConfirmResult 在恢复流中回传，由框架决定放行或拒绝；
        // 此处处理「记住该工具」：落「智能体 × 工具 × 用户」信任决策 + 并入对应会话槽位的权限上下文
        if (confirmed && remember) {
            for (AiConfirmPendingStore.PendingToolCall call : pending.toolCalls()) {
                agentService.rememberToolForAgent(pending.agentId(), pending.userId(), call.toolName());
            }
            AiAgent definition = agentService.getEnabledAgent(pending.agentId());
            // 会话级资源集（REQ-AI-32）：基线与该轮实际装配同源，信任合并后不丢会话选中工具的档位
            AiSessionResourceSet resources =
                sessionResourceService.resolveForSession(sessionService.getSession(sessionId));
            if (pending.expertResume() == null) {
                // 入口智能体自身的暂停：信任并入用户会话槽位（既有口径）
                trustApplier.apply(agentRegistry.get(definition, resources), definition, resources,
                    pending.userId(), sessionId);
            } else {
                // 专家链路的暂停：信任必须并入**专家会话槽位**（userId, routeRunId#expertAgentId），
                // 且基线取专家执行装配（含会话资源集），否则「记住」对该路编排不生效
                trustApplier.applyExpert(agentRegistry.getExpert(definition, resources), definition, resources,
                    pending.userId(), pending.expertResume().expertSessionId());
            }
        }
    }

    /**
     * 查询会话内未决的工具确认（Redis 暂存优先，数据库待确认状态兜底）。
     *
     * <p>暂存已过期的待确认消息仍会返回，并以 {@code expired=true} 标记，避免前端出现无法操作的悬挂卡片。</p>
     *
     * @param sessionId 会话主键
     * @return 待确认列表
     */
    public List<AiChatPendingVo> listPending(Long sessionId) {
        List<AiMessageVo> messages = sessionService.queryPendingMessages(sessionId);
        List<AiChatPendingVo> result = new ArrayList<>(messages.size());
        long ttlSeconds = (long) aiProperties.getChat().getPendingConfirmTtlMinutes() * 60;
        for (AiMessageVo message : messages) {
            AiChatPendingVo vo = new AiChatPendingVo();
            vo.setMessageId(message.getId());
            vo.setExpiresInSec(ttlSeconds);
            AiConfirmPendingStore.PendingConfirm pending = pendingStore.get(sessionId, message.getId()).orElse(null);
            if (pending == null) {
                vo.setExpired(true);
                vo.setToolCalls(List.of());
            } else {
                vo.setExpired(false);
                vo.setReplyId(pending.replyId());
                List<AiChatPendingVo.ToolCall> calls = new ArrayList<>(pending.toolCalls().size());
                for (AiConfirmPendingStore.PendingToolCall call : pending.toolCalls()) {
                    AiChatPendingVo.ToolCall item = new AiChatPendingVo.ToolCall();
                    item.setToolCallId(call.toolCallId());
                    item.setToolName(call.toolName());
                    // 工具中文名与说明：卡片直接展示编码用户看不懂，下发前按 ai_tool 解析（未登记则回退编码）
                    item.setToolLabel(toolLabelResolver.labelOf(call.toolName()));
                    item.setToolDesc(toolLabelResolver.descriptionOf(call.toolName()));
                    item.setInput(call.input());
                    calls.add(item);
                }
                vo.setToolCalls(calls);
            }
            result.add(vo);
        }
        return result;
    }

}
