package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.domain.AiPermissionDecisionLog;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.dto.AiTaskExecution;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.bo.AiChatConfirmBo;
import org.dromara.ai.domain.vo.AiChatPendingVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.ai.service.IAiQuotaService;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSkillService;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
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

    private final IAiSkillService skillService;

    private final IAiMemoryService memoryService;

    /**
     * 场景包 Service：按会话固化的 {@code scenario_pack_id} 取包（无人值守链路 / 兼容路径）
     */
    private final IAiScenarioPackService scenarioPackService;

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
     * 对话附件（聊天上传图片/文档）：每轮现读用户消息的附件并构造多模态内容块
     */
    private final IAiChatAttachmentService attachmentService;

    /**
     * 继承槽位（REQ-AI-31）：每轮按「会话级 &gt; 人级 &gt; 平台默认」合并后注入入口智能体
     */
    private final AiSlotStore slotStore;

    /**
     * 专家编排器（REQ-AI-30 Phase 2）：恢复段用 {@code ConfirmResult} 驱动被 HITL 暂停的专家续跑
     */
    private final AiRouteOrchestrator routeOrchestrator;

    /**
     * AgentScope 事件 → 流式状态与 SSE 信封的桥接器
     */
    private final AiStreamEventBridge streamBridge;

    /**
     * 链路追踪（REQ-AI-28）：记录执行期模型版本 / 上下文清单 / 状态迁移
     */
    private final IAiTraceService traceService;

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
        // 链路追踪（REQ-AI-28）先建：编排运行状态需要它以建立「父链路 → 专家子链路」的关系
        Long traceId = traceService.start(sessionId, definition.getId(), userId, AiTrace.SOURCE_CHAT,
            agentRegistry.snapshotOf(definition, resources));
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
        List<ContentBlock> attachmentBlocks = buildAttachmentBlocks(userMessage);
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
                route.taskContext = buildPinnedTaskContext(userMessage.getContent(), slots, userId, deptId);
            }
        }
        HarnessAgent agent = agentRegistry.get(definition, resources);
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
            .userId(String.valueOf(userId))
            .sessionId(String.valueOf(sessionId))
            // 技能生效范围（REQ-AI-10）：按提问用户覆盖技能白名单（绑定 ∪ 资源集 ∩ 启用 ∩ 可见）
            .put(SkillFilter.class, runtimeSkillFilter(definition.getId(), resources, userId))
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
        applyUserTrust(agent, definition, resources, userId, sessionId);

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
            Disposable disposable = agent.streamEvents(buildUserMsg(userMessage.getContent(), attachmentBlocks), ctx).subscribe(
                event -> dispatchEntryEvent(emitter, state, route, event),
                error -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
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
                    failStream(emitter, message, error);
                    // 编排运行收尾：异常也算终态，便于事后从 ai_route_run 定位本次编排
                    finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
                },
                () -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    if (state.pendingConfirm) {
                        // 暂停也要回填本轮的助手消息主键：否则管理面「编排运行」里这条 pending 记录
                        // 既看不到入口轮 Token 也跳不到消息（REQ-AI-30 Phase 2 第三项）
                        Long pausedMessageId = finishPending(emitter, sessionId, state, durationMs, route);
                        finishRoute(route, pausedMessageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
                        return;
                    }
                    // 指定专家兜底（REQ-AI-31）：pin 存在但模型整轮未调用决策工具（直接自答）
                    // → 服务端补执行一次强制编排 + 整合轮，保证「指定了就一定由它答」
                    if (route != null && route.pinnedExpertId != null && !route.routeInvoked) {
                        drivePinnedFallback(agent, ctx, session, route, state, startNanos, emitter);
                        return;
                    }
                    AiMessage assistant = finish(emitter, sessionId, state, durationMs, null, route);
                    finishRoute(route, assistant == null ? null : assistant.getId(),
                        route != null && route.degraded ? AiRouteRun.STATUS_DEGRADED : AiRouteRun.STATUS_SUCCESS,
                        null, durationMs);
                }
            );
            emitter.onCompletion(disposable::dispose);
            emitter.onTimeout(disposable::dispose);
        } catch (Exception e) {
            throw e instanceof ServiceException serviceException
                ? serviceException : new ServiceException("对话启动失败：" + e.getMessage());
        }
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
     * 编排运行收尾（REQ-AI-30）：把决策动作、专家调用次数与最终状态写回 {@code ai_route_run}。
     *
     * <p>未调用过路由决策工具即 {@code action=self}（入口智能体自答）——自答同样落一行，
     * 便于统计路由命中率。非入口智能体传 {@code route=null}，本方法直接返回。</p>
     *
     * @param route        编排运行状态（可为 null）
     * @param messageId    本轮助手消息主键（可空）
     * @param status       最终状态（success / degraded / aborted）
     * @param degradeReason 降级或中止原因（可空）
     * @param durationMs   本轮总耗时（毫秒）
     */
    private void finishRoute(AiRouteContext route, Long messageId, String status,
                             String degradeReason, long durationMs) {
        if (route == null) {
            return;
        }
        route.messageId = messageId;
        // 恢复段的上下文不含 action（决策发生在首段），不能兜底成自答，否则会覆盖首段的 route 与决策原文
        if (!route.resumed && StringUtils.isBlank(route.action)) {
            route.action = AiRouteRun.ACTION_SELF;
        }
        String reason = StringUtils.isBlank(degradeReason) ? route.degradeReason : degradeReason;
        routeRecorder.finishRun(route.routeRunId, route, status, reason, durationMs);
    }

    /**
     * 无人值守执行一轮对话（定时任务链路）。
     *
     * <p>与对话工作台的区别：无登录上下文（运行身份由调用方显式传入）、无 SSE 输出
     * （同步等待事件流结束）、权限模式固定为 {@link PermissionMode#DONT_ASK}
     * ——被配置为「需确认」的写工具会被框架直接拒绝，既不执行也不会悬挂等待人工确认。</p>
     *
     * @param session     已创建的会话（归属为运行身份用户）
     * @param userMessage 已落库的用户消息（任务提示词）
     * @param runAsUserId 运行身份用户
     * @return 执行结果
     */
    public AiTaskExecution executeUnattended(AiSession session, AiMessage userMessage, Long runAsUserId) {
        long startNanos = System.nanoTime();
        Long sessionId = session.getId();
        AiAgent definition = agentService.getEnabledAgent(session.getAgentId());
        // 无人值守会话（定时任务 / 渠道接入）默认未选场景包，故不接入任何 MCP 工具；
        // 若将来为其指定了包，则同样按包叠加工具与技能、并按包接入 MCP
        AiScenarioPack pack = resolveScenarioPack(session);
        // 配额闸门与实时对话同口径：超限直接失败，原因写入执行记录
        quotaService.check(runAsUserId, definition);
        HarnessAgent agent = agentRegistry.get(definition, pack);
        RuntimeContext ctx = RuntimeContext.builder()
            .userId(String.valueOf(runAsUserId))
            .sessionId(String.valueOf(sessionId))
            // 技能生效范围（REQ-AI-10）：无人值守链路按运行身份用户过滤
            .put(SkillFilter.class, runtimeSkillFilter(definition.getId(), pack, runAsUserId))
            // 子智能体（REQ-AI-04）：无人值守链路必须强制同步，否则后台子任务会在本次执行结束前
            // 就被遗弃（无人追问、无人等待），结果永远无法回收；同样给出硬超时上限
            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, AgentRegistry.SUBAGENT_SYNC_TIMEOUT_SECONDS)
            .build();
        // 无人值守：把「需确认」的写工具按会话降级为「拒绝」，不执行、不悬挂
        AiPermissionSupport.denyAskingTools(agent, String.valueOf(runAsUserId), String.valueOf(sessionId));

        AiStreamState state = new AiStreamState();
        state.sessionId = sessionId;
        state.userId = runAsUserId;
        state.agentId = definition.getId();
        state.modelId = definition.getModelId();
        // 链路追踪（REQ-AI-28）：无人值守链路，来源按会话来源标记（定时任务 / 渠道接入）
        state.traceId = traceService.start(sessionId, definition.getId(), runAsUserId, resolveTraceSource(session),
            agentRegistry.snapshotOf(definition, pack));
        try {
            Flux<AgentEvent> stream = agent.streamEvents(userMessage.getContent(), ctx)
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
            log.error("定时任务执行失败，sessionId={}", sessionId, e);
            String message = AiSseSupport.rootCauseMessage(e);
            traceService.appendTransition(state.traceId, "error", message);
            traceService.accumulate(state.traceId, AiTrace.STATUS_FAILED, durationMs, null,
                null, null, null, message);
            try {
                sessionService.persistAssistantFailure(sessionId, message, durationMs);
            } catch (Exception ex) {
                log.error("落库失败消息异常，sessionId={}", sessionId, ex);
            }
            return new AiTaskExecution(sessionId, null, false, message, durationMs, 0, null, null, null);
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        AiMessage assistant = finish(null, sessionId, state, durationMs, null, null);
        if (assistant == null) {
            return new AiTaskExecution(sessionId, null, false, "回答已生成但保存失败", durationMs, 0, null, null, null);
        }
        return new AiTaskExecution(sessionId, assistant.getId(), true, null, durationMs,
            assistant.getToolCallCount(), assistant.getPromptTokens(), assistant.getCompletionTokens(),
            assistant.getTotalTokens());
    }

    /**
     * 由会话来源推导链路追踪来源（无人值守链路：定时任务 / 渠道接入）。
     *
     * <p>两者的处理口径完全一致（无登录上下文、{@code DONT_ASK}、写工具一律拒绝），
     * 仅来源标记不同，便于链路追踪页按来源区分排障。</p>
     *
     * @param session 会话实体
     * @return 链路追踪来源
     */
    private String resolveTraceSource(AiSession session) {
        return AiSession.SOURCE_CHANNEL.equals(session.getSource()) ? AiTrace.SOURCE_CHANNEL : AiTrace.SOURCE_TASK;
    }

    /**
     * 构造运行期技能白名单（REQ-AI-10）。
     *
     * <p>取「智能体绑定 ∪ 会话资源集技能 ∩ 技能启用 ∩ 提问用户可见」的技能标识；由于
     * {@code SkillFilter.only(...)} 是独立过滤器而非 overlay，它会**整体替换**装配期的
     * builder 级过滤器，而此处已与绑定 / 资源集集合求交，故语义等价且更严。</p>
     *
     * <p>注意框架 {@code SkillFilter} 的既定语义是「决定哪些技能出现在 system prompt」，
     * 不阻止按名加载技能内容；本项目据此把生效范围落在「模型可见面」，
     * 与知识库「检索链路按提问用户过滤」的口径边界见 docs/00 第 38 项。</p>
     *
     * @param agentId   智能体主键
     * @param resources 会话级资源集（REQ-AI-32；可空）
     * @param userId    提问用户主键
     * @return 技能过滤器
     */
    private SkillFilter runtimeSkillFilter(Long agentId, AiSessionResourceSet resources, Long userId) {
        List<Long> extraSkillIds = resources == null ? List.of() : resources.mergedSkillIds();
        List<String> names = skillService.resolveRuntimeSkillFilterKeys(agentId, extraSkillIds, userId);
        return names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names.toArray(new String[0]));
    }

    /**
     * 构造运行期技能白名单（兼容路径：无人值守链路按单场景包）。
     *
     * @param agentId 智能体主键
     * @param pack    会话所选场景包（可为 null）
     * @param userId  提问用户主键
     * @return 技能过滤器
     */
    private SkillFilter runtimeSkillFilter(Long agentId, AiScenarioPack pack, Long userId) {
        List<String> names = skillService.resolveRuntimeSkillFilterKeys(agentId, packSkillIds(pack), userId);
        return names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names.toArray(new String[0]));
    }

    /**
     * 按会话固化的 {@code scenario_pack_id} 取场景包；为空 / 已停用 / 已删除时按「无包」处理。
     *
     * @param session 会话实体
     * @return 场景包；不可用时返回 null
     */
    private AiScenarioPack resolveScenarioPack(AiSession session) {
        return session == null ? null : scenarioPackService.getEnabledPackOrNull(session.getScenarioPackId());
    }

    /**
     * 取场景包内技能主键（未选包时为空）。
     *
     * @param pack 场景包（可为 null）
     * @return 技能主键集合
     */
    private List<Long> packSkillIds(AiScenarioPack pack) {
        return pack == null ? List.of() : scenarioPackService.queryPackSkillIds(pack.getId());
    }

    /**
     * 把「需确认」的写工具在无人值守场景按会话降级为「拒绝」。
     *
     * <p>实现已抽到 {@link AiPermissionSupport#denyAskingTools}（编排链路的专家执行共用同一套改写）。</p>
     *
     * @param agent     已装配的智能体
     * @param userId    运行身份用户
     * @param sessionId 会话主键
     */
    private void denyAskingTools(HarnessAgent agent, String userId, String sessionId) {
        AiPermissionSupport.denyAskingTools(agent, userId, sessionId);
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
            resumeRoute(session, messageId, pending, emitter);
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
            .put(SkillFilter.class, runtimeSkillFilter(definition.getId(), resources, pending.userId()))
            // 继承槽位（REQ-AI-31）：恢复段与首段保持同一份槽位
            .put(AiSlotSet.class, slotStore.merge(pending.userId(), sessionId))
            .build();
        agent.setPermissionMode(ctx, permissionModeStore.resolve());
        // 恢复段同样按提问用户同步信任（首段已同步时此处为空操作）
        applyUserTrust(agent, definition, resources, pending.userId(), sessionId);

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
            Disposable disposable = agent.streamEvents(buildResumeMessages(pending), ctx).subscribe(
                event -> streamBridge.handle(emitter, state, event),
                error -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    log.error("HITL 恢复执行失败，sessionId={}, messageId={}", sessionId, messageId, error);
                    String message = AiSseSupport.rootCauseMessage(error);
                    traceService.appendTransition(state.traceId, "error", "恢复失败：" + message);
                    traceService.accumulate(state.traceId, AiTrace.STATUS_ABORTED, durationMs, null,
                        null, null, null, message);
                    try {
                        sessionService.appendAssistantTurn(messageId, "\n\n" + message,
                            AiMessage.STATUS_ABORTED, durationMs, null, state.toTokenUsage());
                        usageStatService.accumulate(state.userId, state.agentId, state.modelId,
                            state.toTokenUsage(), 0, 0);
                    } catch (Exception e) {
                        log.error("落库恢复失败消息异常，messageId={}", messageId, e);
                    }
                    pendingStore.remove(sessionId, messageId);
                    failStream(emitter, message, error);
                },
                () -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    if (state.pendingConfirm) {
                        // 恢复段内再次命中「需确认」工具（一次提问包含多次写操作）：继续暂停，
                        // 覆盖暂存并再次下发确认卡片；暂存**不能清除**，否则用户无法处置第二次确认
                        finishPendingResume(emitter, sessionId, state, durationMs, messageId, null);
                        return;
                    }
                    finish(emitter, sessionId, state, durationMs, messageId, null);
                    pendingStore.remove(sessionId, messageId);
                }
            );
            emitter.onCompletion(disposable::dispose);
            emitter.onTimeout(disposable::dispose);
        } catch (Exception e) {
            throw e instanceof ServiceException serviceException
                ? serviceException : new ServiceException("恢复执行失败：" + e.getMessage());
        }
    }

    /**
     * 编排链路的恢复段（REQ-AI-30 Phase 2：专家「需确认」的 HITL 贯通）。
     *
     * <p>流程：① 用 {@code ConfirmResult} 驱动**专家**续跑（同一实例 + 同一 {@code expertSessionId}）；
     * ② 若再次命中「需确认」→ 再次落卡（同一条助手消息、覆盖暂存、**不清暂存**）；
     * ③ 拿到专家结论（或降级报告）后**由入口智能体做整合轮**（该轮禁止再路由），
     * 结果**追加更新同一条助手消息**——用户看到的仍是完整的一轮回答。</p>
     *
     * @param session   会话
     * @param messageId 暂停时落库的助手消息主键
     * @param pending   待确认状态（含专家续跑信息与用户处置结果）
     * @param emitter   SSE 发射器
     */
    private void resumeRoute(AiSession session, Long messageId, AiConfirmPendingStore.PendingConfirm pending,
                             SseEmitter emitter) {
        Long sessionId = session.getId();
        Long userId = pending.userId();
        AiConfirmPendingStore.ExpertResume resume = pending.expertResume();
        if (!pendingStore.claim(sessionId, messageId)) {
            throw new ServiceException("该确认正在处理中或已被处理，请稍候");
        }
        AiAgent definition = agentService.getEnabledAgent(session.getAgentId());
        // 会话级资源集（REQ-AI-32）：恢复段与首段同源，供小Z 整合轮与专家续跑共用
        AiSessionResourceSet resources = sessionResourceService.resolveForSession(session);
        HarnessAgent agent = agentRegistry.get(definition, resources);
        Long deptId = LoginHelper.getDeptId();
        // 复用暂停阶段那条链路（取不到才新建）；三目一次性赋值以满足 lambda 捕获要求
        Long pausedTraceId = traceService.findIdByMessage(sessionId, messageId);
        final Long traceId = pausedTraceId != null ? pausedTraceId
            : traceService.start(sessionId, definition.getId(), userId, AiTrace.SOURCE_CHAT,
                agentRegistry.snapshotOf(definition, resources));
        // 恢复段重建编排上下文：续跑记在同一次编排上（routeRunId 取自暂存），候选清单按提问用户现算
        AiRouteContext route = new AiRouteContext(resume.routeRunId(), sessionId, userId, deptId,
            definition.getId(), expertDirectory.candidates(userId, deptId), traceId, emitter);
        // 专家续跑继承同一份会话资源集（REQ-AI-32）
        route.resources = resources;
        // 指定专家（固化路由，REQ-AI-31）：恢复段同样按会话上的「指定专家」解析其候选视图，
        // 供续跑段注入「已指定专家」提示与专家名展示（auto_route='0' 时不在候选清单内，回归 B2）
        route.pinnedExpertId = session.getPinnedExpertId();
        route.pinSource = PIN_SOURCE_USER;
        route.pinnedExpert = expertDirectory.visibleCandidate(userId, deptId, route.pinnedExpertId);
        // 标记为恢复段：本上下文的 action / 决策原文 / 任务片段为空（决策发生在首段），
        // 收尾时不得把它们覆盖回 ai_route_run（详见 AiRouteContext#resumed）
        route.resumed = true;
        AiStreamState state = new AiStreamState();
        state.sessionId = sessionId;
        state.userId = userId;
        state.agentId = definition.getId();
        state.modelId = definition.getModelId();
        state.traceId = traceId;
        route.entryState = state;
        RuntimeContext ctx = RuntimeContext.builder()
            .userId(String.valueOf(userId))
            .sessionId(String.valueOf(sessionId))
            // 技能生效范围（REQ-AI-10）：与首段保持同一份白名单
            .put(SkillFilter.class, runtimeSkillFilter(definition.getId(), resources, userId))
            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, AgentRegistry.SUBAGENT_SYNC_TIMEOUT_SECONDS)
            .put(AiRouteContext.class, route)
            // 继承槽位（REQ-AI-31）：整合轮与首段保持同一份槽位（读 Redis 在请求线程）
            .put(AiSlotSet.class, slotStore.merge(userId, sessionId))
            .build();
        agent.setPermissionMode(ctx, permissionModeStore.resolve());
        applyUserTrust(agent, definition, resources, userId, sessionId);
        traceService.appendTransition(traceId, "resume", "人工确认后恢复专家执行");

        long startNanos = System.nanoTime();
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_START));
        try {
            Mono.fromCallable(() -> routeOrchestrator.resumeExpert(route, resume, buildResumeMessages(pending)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> {
                    if (outcome.paused()) {
                        // 恢复段内再次命中「需确认」：同一条消息上再出一张卡片（暂存不清）
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                        finishPendingResume(emitter, sessionId, state, durationMs, messageId, route);
                        // 同上：再次暂停同样回填助手消息主键（本段复用同一条消息）
                        finishRoute(route, messageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
                        pendingStore.release(sessionId, messageId);
                        return;
                    }
                    // 专家直答修订（2026-10-03）：恢复段不再走整合轮——专家续答已直传用户，
                    // 直接以累积正文收尾（成功）或让小Z 兜底自答（降级）
                    boolean degraded = route.degraded || !route.handoff;
                    if (degraded) {
                        // 降级：`handoff` 已被编排器置回 false，改用入口智能体兜底（其正文正常流出）
                        driveIntegration(agent, ctx, session, messageId, route, state, outcome.report(),
                            INTEGRATION_INTRO_RESUME, startNanos, emitter);
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    AiMessage assistant = finish(emitter, sessionId, state, durationMs, messageId, route);
                    finishRoute(route, assistant == null ? null : assistant.getId(),
                        AiRouteRun.STATUS_SUCCESS, null, durationMs);
                    pendingStore.remove(sessionId, messageId);
                    pendingStore.release(sessionId, messageId);
                }, error -> {
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    String message = AiSseSupport.rootCauseMessage(error);
                    log.error("编排恢复失败，sessionId={}, messageId={}", sessionId, messageId, error);
                    traceService.appendTransition(traceId, "error", "恢复失败：" + message);
                    pendingStore.release(sessionId, messageId);
                    failStream(emitter, message, error);
                    finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
                });
        } catch (Exception e) {
            pendingStore.release(sessionId, messageId);
            throw e instanceof ServiceException serviceException
                ? serviceException : new ServiceException("恢复执行失败：" + e.getMessage());
        }
    }

    /**
     * 恢复段拿到专家结论后，驱动入口智能体完成**整合轮**并把结果追加更新到同一条助手消息。
     *
     * @param agent      入口智能体（已装配）
     * @param ctx        入口智能体的运行期上下文（已带编排上下文，整合轮不再允许路由）
     * @param session    会话
     * @param messageId  暂停时落库的助手消息主键（追加更新复用）
     * @param route      编排运行状态
     * @param state      恢复段的流式累积状态
     * @param report     专家结论报告（或降级报告），作为整合轮的输入
     * @param intro      整合轮的开场指令（HITL 恢复段 / 指定专家兜底段措辞不同）
     * @param startNanos 恢复段开始时间（纳秒）
     * @param emitter    SSE 发射器
     */
    private void driveIntegration(HarnessAgent agent, RuntimeContext ctx, AiSession session, Long messageId,
                                  AiRouteContext route, AiStreamState state, String report, String intro,
                                  long startNanos, SseEmitter emitter) {
        Long sessionId = session.getId();
        // 整合轮禁止再路由：RouteDecisionTool 见该标记即回绝（服务端强约束）
        route.routeDone = true;
        // 兜底轮（专家失败/超时）：专家可能已流出部分正文，先清掉再让小Z 自答，避免两者拼接
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_ANSWER_RESET));
        state.answer.setLength(0);
        String userMessage = intro + report;
        AtomicBoolean finished = new AtomicBoolean(false);
        Disposable disposable = agent.streamEvents(userMessage, ctx).subscribe(
            event -> streamBridge.handle(emitter, state, event),
            error -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                String message = AiSseSupport.rootCauseMessage(error);
                log.error("编排整合轮失败，sessionId={}, messageId={}", sessionId, messageId, error);
                traceService.appendTransition(state.traceId, "error", "整合失败：" + message);
                if (messageId != null) {
                    pendingStore.release(sessionId, messageId);
                }
                failStream(emitter, message, error);
                finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            },
            () -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                AiMessage assistant = finish(emitter, sessionId, state, durationMs, messageId, route);
                finishRoute(route, assistant == null ? null : assistant.getId(),
                    route.degraded ? AiRouteRun.STATUS_DEGRADED : AiRouteRun.STATUS_SUCCESS, null, durationMs);
                // 非 HITL 恢复段（指定专家兜底）没有暂存可清，故按 messageId 判空
                if (messageId != null) {
                    pendingStore.remove(sessionId, messageId);
                    pendingStore.release(sessionId, messageId);
                }
            });
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);
    }

    /**
     * 指定专家的「收尾兜底」（REQ-AI-31）：模型整轮**未调用**决策工具（直接自答）时，
     * 由服务端补执行一次强制编排，再走整合轮，保证「指定了就一定由它答」。
     *
     * <p>与主路径（{@link RouteDecisionTool#runPinned}）的差别只在触发点：主路径由模型调用工具触发，
     * 本路径由服务端在入口轮收尾时触发。两者共用 {@link AiRouteOrchestrator#executePinned}，
     * 故契约校验 / 重试 / 不换专家 / 降级 / 审计口径完全一致。</p>
     *
     * <p>模型这段自答被**丢弃**（下发 {@code answer_reset} 让前端清掉文本片段，服务端清空
     * {@link AiStreamState#answer}）：指定路由的语义是「用户意志覆盖模型决策」，不能把模型的自答
     * 与专家结论拼接交付。工具调用明细照常保留（确已发生，属审计事实）。</p>
     *
     * @param agent      入口智能体（已装配）
     * @param ctx        入口智能体的运行期上下文
     * @param session    会话
     * @param route      编排运行状态（{@code pinnedExpertId} 非空、{@code routeInvoked=false}）
     * @param state      入口轮的流式累积状态
     * @param startNanos 本轮开始时间（纳秒）
     * @param emitter    SSE 发射器
     */
    private void drivePinnedFallback(HarnessAgent agent, RuntimeContext ctx, AiSession session,
                                     AiRouteContext route, AiStreamState state, long startNanos,
                                     SseEmitter emitter) {
        Long sessionId = session.getId();
        // 先钉死审计动作：后续 finishRoute 见 action 非空即不再兜底成 self
        route.action = AiRouteRun.ACTION_PINNED;
        route.decisionJson = pinnedFallbackDecision(route);
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_ANSWER_RESET));
        state.answer.setLength(0);
        Mono.fromCallable(() -> routeOrchestrator.executePinned(route))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(report -> {
                if (route.pendingResume != null) {
                    // 指定专家命中「需确认」：与主路径同款暂停并落确认卡片
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    Long pausedMessageId = finishPending(emitter, sessionId, state, durationMs, route);
                    finishRoute(route, pausedMessageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
                    return;
                }
                if (route.degraded || !route.handoff) {
                    // 降级：`handoff` 已被编排器置回 false，交由入口智能体兜底（其正文正常流出）
                    driveIntegration(agent, ctx, session, null, route, state, report,
                        INTEGRATION_INTRO_PINNED, startNanos, emitter);
                    return;
                }
                // 专家直答修订（2026-10-03）：专家正文已直传用户，直接收尾落库（不再跑整合轮）
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                AiMessage assistant = finish(emitter, sessionId, state, durationMs, null, route);
                finishRoute(route, assistant == null ? null : assistant.getId(),
                    AiRouteRun.STATUS_SUCCESS, null, durationMs);
            }, error -> {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                String message = AiSseSupport.rootCauseMessage(error);
                log.error("指定专家兜底编排失败，sessionId={}", sessionId, error);
                traceService.appendTransition(route.traceId, "error", "指定专家兜底失败：" + message);
                failStream(emitter, message, error);
                finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            });
    }

    /**
     * 指定专家兜底段的决策留档（无模型决策，记一条服务端补执行的说明）。
     *
     * @param route 编排运行状态
     * @return 决策原文（JSON）
     */
    private String pinnedFallbackDecision(AiRouteContext route) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", AiRouteRun.ACTION_PINNED);
        decision.put("pin_source", route.pinSource);
        decision.put("expert_agent_id", route.pinnedExpertId);
        decision.put("fallback", true);
        decision.put("note", "模型本轮未调用 route_decision，服务端补执行强制编排");
        return JsonUtils.toJsonString(decision);
    }

    /**
     * 构造本轮用户消息的附件内容块（文档解析文本内联 + 图片 Base64 图片块）。
     *
     * @param userMessage 已落库的用户消息
     * @return 附件内容块（无附件时为空列表）
     */
    private List<ContentBlock> buildAttachmentBlocks(AiMessage userMessage) {
        List<AiMessageAttachment> attachments = attachmentService.listByMessageId(userMessage.getId());
        if (attachments.isEmpty()) {
            return List.of();
        }
        int inlineMax = aiProperties.getChat().getDocumentInlineMaxChars();
        List<ContentBlock> blocks = new ArrayList<>();
        // 文档：解析文本内联为文本块（应在图片之前，作为可读事实）
        for (AiMessageAttachment attachment : attachments) {
            if (AiMessageAttachment.TYPE_DOCUMENT.equals(attachment.getFileType())) {
                blocks.add(TextBlock.builder()
                    .text("【上传文档：" + attachment.getFileName() + "】\n"
                        + truncateDocument(attachment.getParsedContent(), inlineMax))
                    .build());
            }
        }
        // 图片：读回字节转 Base64 data URI（模型侧按 image_url 下发）
        for (AiMessageAttachment attachment : attachments) {
            if (AiMessageAttachment.TYPE_IMAGE.equals(attachment.getFileType())) {
                blocks.add(buildImageBlock(attachment));
            }
        }
        return blocks;
    }

    /**
     * 把一张图片附件转成 AgentScope 的图片内容块（Base64 来源）。
     *
     * <p>对象存储读取是外部边界：单张图片加载失败时降级为文本占位，避免整轮对话被打断。</p>
     *
     * @param attachment 图片附件
     * @return 图片内容块；加载失败时返回文本占位块
     */
    private ContentBlock buildImageBlock(AiMessageAttachment attachment) {
        try {
            byte[] bytes = attachmentService.downloadBytes(attachment.getOssId());
            String mediaType = StringUtils.isNotBlank(attachment.getMimeType())
                ? attachment.getMimeType() : "image/png";
            return ImageBlock.builder()
                .source(Base64Source.builder()
                    .data(Base64.getEncoder().encodeToString(bytes))
                    .mediaType(mediaType)
                    .build())
                .build();
        } catch (Exception e) {
            log.warn("图片附件加载失败，attachmentId={}", attachment.getId(), e);
            return TextBlock.builder()
                .text("【图片附件 " + attachment.getFileName() + " 加载失败，已跳过】")
                .build();
        }
    }

    /**
     * 用「用户文字 + 附件内容块」构造本轮 user message。
     *
     * @param content         用户输入文字（可空）
     * @param attachmentBlocks 附件内容块（可空）
     * @return 用户消息
     */
    private Msg buildUserMsg(String content, List<ContentBlock> attachmentBlocks) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (StringUtils.isNotBlank(content)) {
            blocks.add(TextBlock.builder().text(content).build());
        }
        if (attachmentBlocks != null && !attachmentBlocks.isEmpty()) {
            blocks.addAll(attachmentBlocks);
        }
        return new UserMessage(blocks);
    }

    /**
     * 按上限截断内联文档文本：保留首尾、中段省略（避免单个大文档撑爆模型上下文窗口）。
     *
     * @param text 文档全量文本
     * @param max  最大字符数（<=0 表示不截断）
     * @return 截断后的文本
     */
    private String truncateDocument(String text, int max) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        int head = max / 2;
        int tail = max - head;
        return text.substring(0, head)
            + "\n……（文档过长，中段已省略）……\n"
            + text.substring(text.length() - tail);
    }

    /**
     * 构造指定路由的 {@code task_context}（P7：**取用户原话**，不做额外的任务提炼调用）。
     *
     * <p>按需附带必要槽位（时区 / 时间窗 / 语言 / 任务线）与提问用户 / 部门，使专家无需追问；
     * 用户与部门仅作上下文陈述，**授权仍由工具侧 {@code AiDataScopeResolver} 判定**。</p>
     *
     * @param question 用户原话
     * @param slots    继承槽位（可空）
     * @param userId   提问用户主键
     * @param deptId   提问用户部门主键（可空）
     * @return 任务片段文本
     */
    private String buildPinnedTaskContext(String question, AiSlotSet slots, Long userId, Long deptId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原话】\n").append(question == null ? "" : question).append('\n');
        if (slots != null && !slots.isEmpty()) {
            sb.append("\n【继承槽位（直接采用，无需向用户追问）】\n").append(slots.render()).append('\n');
        }
        sb.append("\n【上下文】提问用户ID=").append(userId);
        if (deptId != null) {
            sb.append("；部门ID=").append(deptId);
        }
        return sb.toString();
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
        List<AiConfirmPendingStore.PendingDecision> decisions = new ArrayList<>();
        for (AiConfirmPendingStore.PendingToolCall call : pending.toolCalls()) {
            decisions.add(new AiConfirmPendingStore.PendingDecision(call.toolCallId(), confirmed, remember));
        }
        pendingStore.save(new AiConfirmPendingStore.PendingConfirm(pending.sessionId(), pending.messageId(),
            pending.agentId(), pending.userId(), pending.replyId(), pending.toolCalls(), decisions,
            pending.createdAt(), pending.expertResume()));
        decisionService.applyConfirm(bo.getMessageId(), confirmed, remember);
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
                applyUserTrust(agentRegistry.get(definition, resources), definition, resources,
                    pending.userId(), sessionId);
            } else {
                // 专家链路的暂停：信任必须并入**专家会话槽位**（userId, routeRunId#expertAgentId），
                // 且基线取专家执行装配（含会话资源集），否则「记住」对该路编排不生效
                applyExpertUserTrust(agentRegistry.getExpert(definition, resources), definition, resources,
                    pending.userId(), pending.expertResume().expertSessionId());
            }
        }
    }

    /**
     * 把「该用户在该智能体上已记住的工具」并入该会话（{@code (userId, sessionId)} 槽位）的权限上下文。
     *
     * <p><b>为什么必须每轮同步、且只在会话级落规则</b>：① 装配实例是**智能体级、跨用户共享**的，
     * 把某个用户的「记住」写进共享上下文会让同智能体的其他用户也被免确认，故装配期不合并用户级信任；
     * ② 框架在每轮调用开始时都会从状态存储重载 {@code AgentState} 并用其中的权限上下文**重建权限引擎**，
     * 因此恢复流里 {@code ConfirmResult} 附带的规则只对当轮有效（这正是「记住后下次仍弹确认」的原因之一）。
     * 这里以「管理端档位」上下文为基线重建该槽位的上下文：同名 ask 规则在已记住时不再生成
     * （框架判定顺序 deny &gt; ask &gt; 工具自检 &gt; allow，留着 ask 会让「记住」失效），并补 ALLOW（来源 remember）。</p>
     *
     * <p>幂等：当前槽位已应用的 remember 规则集合与库中一致时直接返回，避免每轮重建并持久化会话状态；
     * 撤销（删行）后下一次提问即因集合不一致而摘除 allow、恢复询问。仅影响该槽位，不污染其他用户 / 会话。</p>
     *
     * @param agent      已装配的智能体
     * @param definition 智能体定义
     * @param resources  会话级资源集（REQ-AI-32；用于取与装配一致的「管理端档位」基线）
     * @param userId     提问用户主键
     * @param sessionId  会话主键
     */
    private void applyUserTrust(HarnessAgent agent, AiAgent definition, AiSessionResourceSet resources,
                                Long userId, Long sessionId) {
        applyUserTrust(agent, definition, resources, userId, sessionId == null ? null : String.valueOf(sessionId));
    }

    /**
     * 把信任并入指定会话槽位（字符串键版本）。
     *
     * <p>专家链路的会话键不是雪花主键，而是 {@code routeRunId#expertAgentId} 这样的字符串，
     * 故单独提供字符串版本（REQ-AI-30 Phase 2 HITL 贯通：确认卡片上的「记住该工具」要落到专家槽位）。</p>
     *
     * @param agent        已装配的智能体
     * @param definition   智能体定义
     * @param resources    会话级资源集（REQ-AI-32）
     * @param userId       提问用户
     * @param sessionKey   AgentScope 的 sessionId 字符串键
     */
    private void applyUserTrust(HarnessAgent agent, AiAgent definition, AiSessionResourceSet resources,
                                Long userId, String sessionKey) {
        if (agent == null || definition == null || userId == null || StringUtils.isBlank(sessionKey)) {
            return;
        }
        AiPermissionSupport.applyUserToolTrust(agent, agentRegistry.permissionContextOf(definition, resources),
            agentService.trustedToolCodes(definition.getId(), userId), String.valueOf(userId), sessionKey);
    }

    /**
     * 把「该用户在该专家上已记住的工具」并入**专家会话槽位**（REQ-AI-30 Phase 2 HITL 贯通）。
     *
     * <p>与入口链路的差别只在两处：槽位键是 {@code routeRunId#expertAgentId} 字符串，
     * 基线取<b>专家执行装配</b>的权限上下文（子智能体协调工具与编排协议工具均不放行）。</p>
     *
     * @param agent          已装配的专家实例（{@code getExpert}）
     * @param definition     专家定义
     * @param resources      会话级资源集（REQ-AI-32）
     * @param userId         提问用户
     * @param expertSessionId 专家会话标识
     */
    private void applyExpertUserTrust(HarnessAgent agent, AiAgent definition, AiSessionResourceSet resources,
                                      Long userId, String expertSessionId) {
        if (agent == null || definition == null || userId == null || StringUtils.isBlank(expertSessionId)) {
            return;
        }
        AiPermissionSupport.applyUserToolTrust(agent,
            agentRegistry.permissionContextOfExpert(definition, resources),
            agentService.trustedToolCodes(definition.getId(), userId), String.valueOf(userId), expertSessionId);
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
                    item.setInput(call.input());
                    calls.add(item);
                }
                vo.setToolCalls(calls);
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 构造恢复用消息（携带用户对工具调用的处置结果）。
     *
     * @param pending 待确认状态
     * @return 恢复消息列表
     */
    private List<Msg> buildResumeMessages(AiConfirmPendingStore.PendingConfirm pending) {
        Map<String, AiConfirmPendingStore.PendingToolCall> toolCalls = new LinkedHashMap<>();
        for (AiConfirmPendingStore.PendingToolCall call : pending.toolCalls()) {
            toolCalls.put(call.toolCallId(), call);
        }
        List<ConfirmResult> results = new ArrayList<>();
        boolean rejectedAll = true;
        for (AiConfirmPendingStore.PendingDecision decision : pending.decisions()) {
            AiConfirmPendingStore.PendingToolCall call = toolCalls.get(decision.toolCallId());
            if (call == null) {
                continue;
            }
            // 必须带回 content 与 ASKING 状态：框架恢复执行时按 ToolUseBlock.getContent() 校验入参，
            // 缺失会报 argument "content" is null（详见 docs/00 第 29 项）
            ToolUseBlock block = new ToolUseBlock(call.toolCallId(), call.toolName(), call.input(),
                call.content(), null, ToolCallState.ASKING);
            List<PermissionRule> rules = decision.confirmed() && decision.remember()
                ? List.of(new PermissionRule(call.toolName(), null, PermissionBehavior.ALLOW,
                    AiPermissionDecisionLog.SOURCE_REMEMBER))
                : null;
            results.add(new ConfirmResult(decision.confirmed(), block, rules));
            if (decision.confirmed()) {
                rejectedAll = false;
            }
        }
        String text = rejectedAll
            ? "用户拒绝了上述工具调用，请不要重复请求执行，改为基于已有信息作答或明确说明无法完成。"
            : "用户已确认执行上述工具调用，请继续。";
        Msg msg = Msg.builder()
            .role(MsgRole.USER)
            .textContent(text)
            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
            .build();
        return List.of(msg);
    }


    /**
     * 正常收尾：落库助手回答与工具明细（恢复段为追加更新同一条消息），下发 done 并关闭流。
     *
     * @param emitter         SSE 发射器；无人值守链路传 null（不下发事件、不关闭流）
     * @param sessionId       会话主键
     * @param state           流式累积状态
     * @param durationMs      本次耗时（毫秒）
     * @param resumeMessageId 恢复段对应的助手消息主键；非空时追加更新同一条消息
     * @param route           编排运行状态（可为 null）：非空时并入专家调用产生的工具明细
     * @return 落库的助手消息；落库失败时返回 null
     */
    private AiMessage finish(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                             Long resumeMessageId, AiRouteContext route) {
        List<AiToolCallLog> logs = buildToolLogs(state, route);
        String replyId = state.replyId;
        ChatTokenUsage tokens = state.toTokenUsage();
        // 专家直答接管态（REQ-AI-30 直答修订）：回答正文取专家完整正文，而非入口轮（已被丢弃的）正文
        String content = route != null && route.handoff && route.handoffAnswer.length() > 0
            ? route.handoffAnswer.toString() : state.answer.toString();
        AiMessage assistant;
        try {
            if (resumeMessageId == null) {
                assistant = sessionService.persistAssistantTurn(sessionId, content,
                    replyId, durationMs, logs, tokens, state.userId);
            } else {
                assistant = sessionService.appendAssistantTurn(resumeMessageId, content,
                    AiMessage.STATUS_NORMAL, durationMs, logs, tokens);
            }
        } catch (Exception e) {
            log.error("落库助手消息异常，sessionId={}", sessionId, e);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", EVENT_ERROR);
            payload.put("message", "回答已生成但保存失败：" + e.getMessage());
            AiSseSupport.send(emitter, payload);
            AiSseSupport.complete(emitter);
            return null;
        }
        recordAllowDecisions(state, assistant.getId(), resumeMessageId);
        // 专家调用的工具同样补「实际执行」的决策留痕（REQ-AI-30 Phase 2）：按专家维度记账，
        // 被拒绝（DENIED，多为「需确认」档）的不记，避免出现与实际结果相反的「允许」行
        recordExpertAllowDecisions(route, assistant.getId(), state);
        // 链路追踪（REQ-AI-28）：回填消息主键并累加收尾信息（恢复段累加，不覆盖首段）
        traceService.attachMessage(state.traceId, assistant.getId());
        traceService.accumulate(state.traceId, AiTrace.STATUS_SUCCESS, durationMs, logs.size(),
            tokens == null ? null : tokens.promptTokens(), tokens == null ? null : tokens.completionTokens(),
            tokens == null ? null : tokens.totalTokens(), null);
        traceService.appendTransition(state.traceId, "done",
            "工具 " + logs.size() + " 次" + (tokens == null ? "" : "，合计 " + tokens.totalTokens() + " tokens"));
        // 恢复段不重复计一次请求数（同一次提问的两段生成）
        usageStatService.accumulate(state.userId, state.agentId, state.modelId, tokens, logs.size(),
            resumeMessageId == null ? 1 : 0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_DONE);
        payload.put("messageId", assistant.getId());
        payload.put("replyId", replyId);
        payload.put("durationMs", durationMs);
        payload.put("toolCallCount", logs.size());
        if (tokens != null) {
            payload.put("totalTokens", tokens.totalTokens());
        }
        AiSseSupport.send(emitter, payload);
        AiSseSupport.complete(emitter);
        // 长期记忆自动合并（REQ-AI-09）：本轮已产生新的每日流水，按节流异步把流水并入 MEMORY.md，
        // 否则新对话只会看到上一次的长期记忆（框架自动合并在用户隔离目录下恒跳过，见 AiMemoryServiceImpl）
        try {
            memoryService.consolidateIfDue(state.agentId, state.userId);
        } catch (Exception e) {
            log.warn("提交长期记忆自动合并失败：agent={}, user={}, err={}",
                state.agentId, state.userId, e.getMessage());
        }
        return assistant;
    }

    /**
     * 因等待人工确认而暂停：落库待确认消息、写决策日志、暂存 Redis 待确认状态并下发确认事件。
     *
     * @param emitter    SSE 发射器
     * @param sessionId  会话主键
     * @param state      流式累积状态
     * @param durationMs 暂停前耗时（毫秒）
     * @param route      编排运行状态（可为 null）；专家链路的暂停需据此落专家续跑信息
     * @return 本次落库的待确认助手消息主键（落库失败时为 null），供编排运行回填 {@code message_id}
     */
    private Long finishPending(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                               AiRouteContext route) {
        AiConfirmPendingStore.ExpertResume expertResume = route == null ? null : route.pendingResume;
        // 专家链路的暂停：正文用**平台文案**，不落入口智能体在暂停后可能续写的残句
        String content = expertResume == null ? state.answer.toString() : PENDING_EXPERT_TEXT;
        AiMessage assistant;
        try {
            // 本段已完成（含被拒）的工具调用照常落明细：暂停发生在工具首次请求之后，
            // 之前已执行的读/内置工具不应因为「本轮暂停」而从审计里消失
            assistant = sessionService.persistPendingTurn(sessionId, content, state.pendingReplyId, durationMs,
                buildDoneToolLogs(state, route), state.toTokenUsage(), state.userId);
        } catch (Exception e) {
            log.error("落库待确认消息异常，sessionId={}", sessionId, e);
            sendPendingSaveError(emitter, e);
            return null;
        }
        afterPendingPersisted(emitter, state, durationMs, assistant, 1, expertResume);
        return assistant.getId();
    }

    /**
     * 恢复段内再次因等待人工确认而暂停（一次提问包含多次写操作）。
     *
     * <p>与首段的差异有两处：① **追加更新同一条助手消息**（而非新建），使多张确认卡片挂在同一条回复上，
     * 前端沿用同一 {@code messageId} 即可提交确认；② 状态回写为「待确认」，保证刷新后
     * {@code /ai/chat/session/{id}/pending} 仍能还原卡片。请求数不再重复计入（与恢复段口径一致）。</p>
     *
     * @param emitter    SSE 发射器
     * @param sessionId  会话主键
     * @param state      流式累积状态
     * @param durationMs 本段耗时（毫秒）
     * @param messageId  暂停阶段落库的助手消息主键（恢复段复用同一条消息）
     * @param route      编排运行状态（专家链路再次暂停时非空且携新的续跑信息；入口自身暂停为 null）
     */
    private void finishPendingResume(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                                     Long messageId, AiRouteContext route) {
        AiConfirmPendingStore.ExpertResume expertResume = route == null ? null : route.pendingResume;
        // 专家链路的暂停文案在首次暂停时已写入同一条消息，再次暂停不再重复追加（空串＝只回写状态）
        String content = expertResume == null ? state.answer.toString() : "";
        AiMessage assistant;
        try {
            // 同上：暂停前已完成的工具调用（如用户刚确认并已执行的写操作）必须落行，
            // 否则恢复段换了新的编排上下文，这些明细就丢了
            assistant = sessionService.appendAssistantTurn(messageId, content, AiMessage.STATUS_PENDING_CONFIRM,
                durationMs, buildDoneToolLogs(state, route), state.toTokenUsage());
        } catch (Exception e) {
            log.error("落库待确认消息异常（恢复段），sessionId={}, messageId={}", sessionId, messageId, e);
            sendPendingSaveError(emitter, e);
            return;
        }
        afterPendingPersisted(emitter, state, durationMs, assistant, 0, expertResume);
    }

    /**
     * 待确认落库后的公共收尾：用量、链路、决策留痕、暂存与确认卡片下发。
     *
     * @param emitter      SSE 发射器
     * @param state        流式累积状态
     * @param durationMs   本段耗时（毫秒）
     * @param assistant    已落库的待确认助手消息
     * @param requestCount 本段计入的请求数（首段 1，恢复段 0，避免同一次提问重复计数）
     */
    private void afterPendingPersisted(SseEmitter emitter, AiStreamState state, long durationMs,
                                       AiMessage assistant, int requestCount,
                                       AiConfirmPendingStore.ExpertResume expertResume) {
        ChatTokenUsage tokens = state.toTokenUsage();
        usageStatService.accumulate(state.userId, state.agentId, state.modelId, tokens, 0, requestCount);
        // 链路追踪（REQ-AI-28）：暂停也是一种终态，回填消息主键并记录待确认工具
        traceService.attachMessage(state.traceId, assistant.getId());
        traceService.accumulate(state.traceId, AiTrace.STATUS_PENDING_CONFIRM, durationMs, 0,
            tokens == null ? null : tokens.promptTokens(), tokens == null ? null : tokens.completionTokens(),
            tokens == null ? null : tokens.totalTokens(), null);
        traceService.appendTransition(state.traceId, "pending_confirm",
            "等待人工确认：" + state.pendingToolCalls.stream()
                .map(AiConfirmPendingStore.PendingToolCall::toolName).toList());
        // 待确认工具调用的归属：专家链路的暂停记到**专家**头上（入口轮只做转述），
        // 这样决策日志与「记住该工具」的信任维度都落在「专家 × 工具 × 用户」上
        Long pendingAgentId = state.pendingAgentId == null ? state.agentId : state.pendingAgentId;
        for (AiConfirmPendingStore.PendingToolCall call : state.pendingToolCalls) {
            decisionService.recordAsk(state.sessionId, assistant.getId(), pendingAgentId, state.userId,
                call.toolName(), call.toolName(), JsonUtils.toJsonString(call.input()));
        }
        // 覆盖式保存：第二次及以后的暂停同样落在该 (sessionId, messageId) 槽位上，
        // decisions 置空以要求用户重新处置
        pendingStore.save(new AiConfirmPendingStore.PendingConfirm(assistant.getSessionId(), assistant.getId(),
            pendingAgentId, state.userId, state.pendingReplyId, state.pendingToolCalls, null,
            LocalDateTime.now().toString(), expertResume));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_TOOL_CONFIRM);
        payload.put("messageId", assistant.getId());
        payload.put("replyId", state.pendingReplyId);
        payload.put("expiresInSec", aiProperties.getChat().getPendingConfirmTtlMinutes() * 60);
        payload.put("toolCalls", state.pendingToolCalls);
        AiSseSupport.send(emitter, payload);
        emitter.complete();
    }

    /**
     * 待确认消息落库失败时的收尾（提示 + 关闭流）。
     *
     * @param emitter SSE 发射器
     * @param cause   异常
     */
    private void sendPendingSaveError(SseEmitter emitter, Exception cause) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_ERROR);
        payload.put("message", "等待确认的消息保存失败：" + cause.getMessage());
        AiSseSupport.send(emitter, payload);
        emitter.complete();
    }

    /**
     * 为「未被配置为需确认」而已实际执行的工具补写决策日志。
     *
     * <p>恢复段执行的是用户已确认的 ASK 工具，其决策已由 ASK 行留痕，故不再补写 ALLOW 行。</p>
     *
     * @param state           流式累积状态
     * @param messageId       助手消息主键
     * @param resumeMessageId 恢复段对应的助手消息主键（非空表示当前为恢复段）
     */
    private void recordAllowDecisions(AiStreamState state, Long messageId, Long resumeMessageId) {
        if (resumeMessageId != null) {
            return;
        }
        for (AiToolCallAcc acc : state.tools.values()) {
            if (acc.state == null || StringUtils.isBlank(acc.toolName)) {
                continue;
            }
            decisionService.recordAllow(state.sessionId, messageId, state.agentId, state.userId,
                acc.toolName, acc.toolName, acc.input.toString(), acc.durationMs);
        }
    }

    /**
     * 为**专家调用**中实际执行的工具补写决策日志（REQ-AI-30 Phase 2）。
     *
     * <p>专家工具调用与入口轮明细一起在收尾落库，故此处按其 {@code agentId}（专家主键）补
     * {@code ai_permission_decision_log} 的「实际执行」行。判定口径与入口轮一致：
     * **未拿到结果状态（{@code state} 为空）与被拒绝（{@code DENIED}，「需确认」档被改写为拒绝）
     * 的一律不记**，避免留下与实际结果相反的「允许」行。</p>
     *
     * @param route  编排运行状态（可为 null：普通智能体对话与无人值守链路）
     * @param messageId 本轮助手消息主键
     * @param state  入口轮累积状态（取提问用户）
     */
    private void recordExpertAllowDecisions(AiRouteContext route, Long messageId, AiStreamState state) {
        if (route == null) {
            return;
        }
        for (AiToolCallLog log : route.expertToolLogs) {
            if (log.getAgentId() == null || StringUtils.isBlank(log.getToolName()) || StringUtils.isBlank(log.getState())
                || ToolResultState.DENIED.name().equals(log.getState())) {
                continue;
            }
            decisionService.recordAllow(log.getSessionId(), messageId, log.getAgentId(), state.userId,
                log.getToolName(), log.getToolName(), log.getToolInput(), log.getDurationMs());
        }
    }

    /**
     * 由累积状态构造工具调用明细。
     *
     * <p>「未获批的写工具尚未执行、不计入明细」由调用路径保证：暂停收尾只落**已产生结果**的调用
     * （见 {@link #buildDoneToolLogs}），正在等待确认的工具不落行；恢复段执行的是用户已确认的工具，正常计次。</p>
     *
     * @param state 流式累积状态
     * @return 工具调用明细列表
     */
    private List<AiToolCallLog> buildToolLogs(AiStreamState state) {
        return state.toToolLogs(aiProperties.getChat().getToolOutputPreviewChars(), state.agentId);
    }

    /**
     * 由累积状态构造工具调用明细，并并入**专家调用**产生的明细（REQ-AI-30 Phase 2）。
     *
     * <p>专家内部执行的工具调用此前从不落库，写操作因此不可审计；现由编排器把每次专家调用的
     * 工具明细累积到 {@link AiRouteContext#expertToolLogs}，在收尾时与入口轮明细**合并一次写入**
     * （同一助手消息、同一会话），使「专家执行了哪些写操作」可查、可归属于具体专家。</p>
     *
     * @param state 入口轮的流式累积状态
     * @param route 编排运行状态（可为 null：普通智能体对话与无人值守链路）
     * @return 工具调用明细列表
     */
    private List<AiToolCallLog> buildToolLogs(AiStreamState state, AiRouteContext route) {
        List<AiToolCallLog> logs = new ArrayList<>(buildToolLogs(state));
        if (route != null && !route.expertToolLogs.isEmpty()) {
            logs.addAll(route.expertToolLogs);
        }
        return logs;
    }

    /**
     * 构造暂停收尾要落库的工具明细：只含**已产生结果**的调用。
     *
     * <p>暂停时段内已完成的工具（成功 / 被拒 / 失败）必须落行，否则「用户确认后已执行的写操作」会因为
     * 它发生在被后续段取代的那一段而丢失审计（恢复段用的是新的编排上下文，
     * 前一段的明细只在内存里）。正在等待确认的工具尚无结果，按口径不落行。</p>
     *
     * @param state 入口轮的流式累积状态
     * @param route 编排运行状态（可为 null：普通智能体对话）
     * @return 工具调用明细列表（仅已产生结果者）
     */
    private List<AiToolCallLog> buildDoneToolLogs(AiStreamState state, AiRouteContext route) {
        return buildToolLogs(state, route).stream()
            .filter(log -> log.getState() != null)
            .toList();
    }

    /**
     * 记录首个可用的事件 replyId（同一轮对话内 replyId 一致）。
     *
     * @param state   流式累积状态
     * @param replyId 事件携带的 replyId
     */
    private void captureReplyId(AiStreamState state, String replyId) {
        if (state.replyId == null && StringUtils.isNotBlank(replyId)) {
            state.replyId = replyId;
        }
    }

    /**
     * 下发失败事件并终止 SSE 流（错误信封的唯一构造点）。
     *
     * @param emitter SSE 发射器；为 null 时（无人值守链路）只丢弃 SSE 动作
     * @param message 失败说明（已取根因）
     * @param error   原始异常，用于终止流并触发前端错误回调
     */
    private void failStream(SseEmitter emitter, String message, Throwable error) {
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_ERROR);
        payload.put("message", message);
        AiSseSupport.send(emitter, payload);
        if (emitter != null) {
            emitter.completeWithError(error);
        }
    }

}
