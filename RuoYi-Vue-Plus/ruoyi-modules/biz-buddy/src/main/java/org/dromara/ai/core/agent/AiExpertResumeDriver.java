package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编排链路的专家续跑驱动（REQ-AI-30 Phase 2：专家「需确认」的 HITL 贯通）。
 *
 * <p>从 {@link AiChatFacade} 抽出的恢复段：用 {@code ConfirmResult} 驱动被 HITL 暂停的**专家**续跑，
 * 拿到专家结论后按降级与否决定走入口智能体整合轮（{@link AiIntegrationDriver#driveIntegration}）
 * 还是直接收尾；收尾统一交 {@link AiTurnFinisher}。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiExpertResumeDriver {

    /**
     * 事件类型：开始
     */
    private static final String EVENT_START = "start";

    /**
     * 用户主动停止本轮的说明文案（落链路追踪 / 编排运行的中止原因）
     */
    private static final String CANCELLED_TEXT = "用户停止生成";

    /**
     * 整合轮开场指令：HITL 恢复段（专家在用户确认后续跑完毕）
     */
    private static final String INTEGRATION_INTRO_RESUME =
        "用户已确认执行。以下是专家返回的结论，请按整合纪律输出最终回答：\n\n";

    /**
     * 指定专家的来源标记：用户指定（写入 {@code ai_route_run.decision_json} 便于复盘）
     */
    private static final String PIN_SOURCE_USER = "user";

    private final IAiSessionService sessionService;

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    private final AiConfirmPendingStore pendingStore;

    /**
     * 链路追踪（REQ-AI-28）：记录执行期模型版本 / 上下文清单 / 状态迁移
     */
    private final IAiTraceService traceService;

    /**
     * 会话级资源集（REQ-AI-32）：实时对话链路每轮现读，小Z 与专家共用同一份
     */
    private final IAiSessionResourceService sessionResourceService;

    /**
     * 领域专家名录（REQ-AI-30）：入口智能体每轮现算候选专家清单
     */
    private final AiExpertDirectory expertDirectory;

    /**
     * 继承槽位（REQ-AI-31）：每轮按「会话级 &gt; 人级 &gt; 平台默认」合并后注入入口智能体
     */
    private final AiSlotStore slotStore;

    /**
     * 全局权限模式为运行期可变配置，共享单例必须在每轮对话前按 RuntimeContext 应用
     */
    private final AiPermissionModeStore permissionModeStore;

    /**
     * 专家编排器（REQ-AI-30 Phase 2）：恢复段用 {@code ConfirmResult} 驱动被 HITL 暂停的专家续跑
     */
    private final AiRouteOrchestrator routeOrchestrator;

    /**
     * 单次执行取消注册表：对话页「停止」与 SSE 断连据此取消后端执行（AgentScope 2.0.4）
     */
    private final AiRunRegistry runRegistry;

    /**
     * 收尾落库与留痕（R3 拆出）
     */
    private final AiTurnFinisher turnFinisher;

    /**
     * 整合轮驱动（R4 拆出）：降级时由入口智能体兜底整合
     */
    private final AiIntegrationDriver integrationDriver;

    /**
     * 编排 / 运行期无状态辅助（技能白名单、场景包、来源、任务片段）
     */
    private final AiRouteSupport routeSupport;

    /**
     * 「记住该工具」用户级信任的落位（入口链路与专家链路）
     */
    private final AiTrustApplier trustApplier;

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
    void resumeRoute(AiSession session, Long messageId, AiConfirmPendingStore.PendingConfirm pending,
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
            .put(SkillFilter.class, routeSupport.runtimeSkillFilter(definition.getId(), resources, userId))
            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, AgentRegistry.SUBAGENT_SYNC_TIMEOUT_SECONDS)
            .put(AiRouteContext.class, route)
            // 继承槽位（REQ-AI-31）：整合轮与首段保持同一份槽位（读 Redis 在请求线程）
            .put(AiSlotSet.class, slotStore.merge(userId, sessionId))
            .build();
        agent.setPermissionMode(ctx, permissionModeStore.resolve());
        trustApplier.apply(agent, definition, resources, userId, sessionId);
        traceService.appendTransition(traceId, "resume", "人工确认后恢复专家执行");

        long startNanos = System.nanoTime();
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_START));
        // 专家续跑是阻塞式编排调用：登记「dispose 该 Mono」为取消句柄（中断 boundedElastic 线程）
        AtomicReference<Disposable> holder = new AtomicReference<>();
        AiRunRegistry.Cancelable cancelable = () -> {
            Disposable current = holder.get();
            if (current == null || current.isDisposed()) {
                return false;
            }
            current.dispose();
            return true;
        };
        runRegistry.register(sessionId, cancelable);
        // 收尾幂等：外层 dispose（doOnCancel）、内层专家被中断（route.cancelled）、异常三者只会生效一个，
        // 避免「内层取消」与「外层 dispose」同时触发导致重复落库 / 重复 aborted
        AtomicBoolean settled = new AtomicBoolean(false);
        try {
            Disposable subscription = Mono.fromCallable(
                    () -> routeOrchestrator.resumeExpert(route, resume, AiResumeMessageFactory.build(pending)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnCancel(() -> {
                    // 用户停止 / SSE 断连：本段无 agent 事件回调可依，取消时就地按「已中止」收尾
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    traceService.appendTransition(traceId, "cancelled", "用户停止专家续跑");
                    turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, CANCELLED_TEXT, durationMs);
                    pendingStore.release(sessionId, messageId);
                })
                .doFinally(signal -> runRegistry.unregister(sessionId, cancelable))
                .subscribe(outcome -> {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    if (route.cancelled) {
                        // 用户停止：专家续跑已被中断（编排器置 route.cancelled），按「已中止」收尾（不降级）
                        long cancelledMs = (System.nanoTime() - startNanos) / 1_000_000;
                        turnFinisher.finishCancelled(emitter, sessionId, state, cancelledMs, messageId, route);
                        pendingStore.remove(sessionId, messageId);
                        pendingStore.release(sessionId, messageId);
                        return;
                    }
                    if (outcome.paused()) {
                        // 恢复段内再次命中「需确认」：同一条消息上再出一张卡片（暂存不清）
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                        turnFinisher.finishPendingResume(emitter, sessionId, state, durationMs, messageId, route);
                        // 同上：再次暂停同样回填助手消息主键（本段复用同一条消息）
                        turnFinisher.finishRoute(route, messageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
                        pendingStore.release(sessionId, messageId);
                        return;
                    }
                    // 专家直答修订（2026-10-03）：恢复段不再走整合轮——专家续答已直传用户，
                    // 直接以累积正文收尾（成功）或让小Z 兜底自答（降级）
                    boolean degraded = route.degraded || !route.handoff;
                    if (degraded) {
                        // 降级：`handoff` 已被编排器置回 false，改用入口智能体兜底（其正文正常流出）
                        integrationDriver.driveIntegration(agent, ctx, session, messageId, route, state, outcome.report(),
                            INTEGRATION_INTRO_RESUME, startNanos, emitter);
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    AiMessage assistant = turnFinisher.finish(emitter, sessionId, state, durationMs, messageId, route);
                    turnFinisher.finishRoute(route, assistant == null ? null : assistant.getId(),
                        AiRouteRun.STATUS_SUCCESS, null, durationMs);
                    pendingStore.remove(sessionId, messageId);
                    pendingStore.release(sessionId, messageId);
                }, error -> {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    String message = AiSseSupport.rootCauseMessage(error);
                    log.error("编排恢复失败，sessionId={}, messageId={}", sessionId, messageId, error);
                    traceService.appendTransition(traceId, "error", "恢复失败：" + message);
                    pendingStore.release(sessionId, messageId);
                    turnFinisher.failStream(emitter, message, error);
                    turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
                });
            holder.set(subscription);
        } catch (Exception e) {
            runRegistry.unregister(sessionId, cancelable);
            pendingStore.release(sessionId, messageId);
            throw e instanceof ServiceException serviceException
                ? serviceException : new ServiceException("恢复执行失败：" + e.getMessage());
        }
    }

}
