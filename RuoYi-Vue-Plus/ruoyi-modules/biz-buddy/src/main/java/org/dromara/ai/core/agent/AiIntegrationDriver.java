package org.dromara.ai.core.agent;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 整合轮与指定专家兜底的编排驱动（REQ-AI-30 / REQ-AI-31）。
 *
 * <p>从 {@link AiChatFacade} 抽出的「非主链路的编排驱动」：HITL 恢复段拿到专家结论后由入口智能体
 * 做整合轮；指定专家整轮未调用决策工具时由服务端补执行一次强制编排。二者最终都收口到
 * {@link AiTurnFinisher} 做落库与留痕。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiIntegrationDriver {

    /**
     * 事件类型：清空本轮已下发的自答正文（REQ-AI-31 指定专家兜底）。
     */
    private static final String EVENT_ANSWER_RESET = "answer_reset";

    /**
     * 用户主动停止本轮的说明文案（落链路追踪 / 编排运行的中止原因）
     */
    private static final String CANCELLED_TEXT = "用户停止生成";

    /**
     * 整合轮开场指令：指定专家兜底段（模型未调用决策工具，服务端补执行强制编排）
     */
    private static final String INTEGRATION_INTRO_PINNED =
        "本轮用户已指定专家。以下是该专家返回的结论，请按整合纪律输出最终回答：\n\n";

    /**
     * 整合轮开场指令：闸门短路「专家未接管」降级段（docs/21 §5.7）。
     *
     * <p>与 {@link #INTEGRATION_INTRO_PINNED}（用户主动指定专家）刻意区分措辞：本段是**平台判定**
     * 交由专家处理、但该专家失败 / 超时 / 空正文未给出结论（{@code handoff=false}），
     * 故须如实说明是「平台判定」而非「用户指定」，并要求小Z 据降级报告告知用户、尽量自行作答。</p>
     *
     * <p>可见性为**包级**（同包的 {@link AiChatFacade} 需引用）：闸门短路路径没有入口轮，
     * 专家未接管时由门面把本常量作为 {@link #driveIntegration} 的开场指令传入。</p>
     */
    static final String INTEGRATION_INTRO_JUDGE_DEGRADED =
        "本轮由平台判定交由专家处理，但该专家未能给出结论。以下是平台返回的说明，请据此如实告知用户并尽量自行回答：\n\n";

    private final IAiSessionService sessionService;

    private final AiConfirmPendingStore pendingStore;

    /**
     * 链路追踪（REQ-AI-28）：记录执行期模型版本 / 上下文清单 / 状态迁移
     */
    private final IAiTraceService traceService;

    /**
     * 专家编排器（REQ-AI-30 Phase 2）：恢复段用 {@code ConfirmResult} 驱动被 HITL 暂停的专家续跑
     */
    private final AiRouteOrchestrator routeOrchestrator;

    /**
     * AgentScope 事件 → 流式状态与 SSE 信封的桥接器
     */
    private final AiStreamEventBridge streamBridge;

    /**
     * 单次执行取消注册表：对话页「停止」与 SSE 断连据此取消后端执行（AgentScope 2.0.4）
     */
    private final AiRunRegistry runRegistry;

    /**
     * 收尾落库与留痕（R3 拆出）
     */
    private final AiTurnFinisher turnFinisher;

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
    void driveIntegration(HarnessAgent agent, RuntimeContext ctx, AiSession session, Long messageId,
                          AiRouteContext route, AiStreamState state, String report, String intro,
                          long startNanos, SseEmitter emitter) {
        Long sessionId = session.getId();
        // 整合轮禁止再路由：RouteDecisionTool 见该标记即回绝（服务端强约束）
        route.routeDone = true;
        // 兜底轮（专家失败/超时）：专家可能已流出部分正文，先清掉再让小Z 自答，避免两者拼接
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_ANSWER_RESET));
        state.answer.setLength(0);
        // 对话期进度：整合轮是一次完整的模型调用（可能数秒），播报「在汇总」避免正文重开前的静默
        AiPhaseSupport.emit(emitter, state, AiPhaseSupport.PHASE_INTEGRATING, "小Z 正在汇总专家结论…", null);
        String userMessage = intro + report;
        AtomicBoolean finished = new AtomicBoolean(false);
        AgentRun<AgentEvent> run = agent.prepareRun(List.of(new UserMessage(userMessage)), ctx);
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
                    if (messageId != null) {
                        pendingStore.release(sessionId, messageId);
                    }
                    turnFinisher.finishCancelled(emitter, sessionId, state, durationMs, messageId, route);
                    return;
                }
                String message = AiSseSupport.rootCauseMessage(error);
                log.error("编排整合轮失败，sessionId={}, messageId={}", sessionId, messageId, error);
                traceService.appendTransition(state.traceId, "error", "整合失败：" + message);
                if (messageId != null) {
                    pendingStore.release(sessionId, messageId);
                }
                turnFinisher.failStream(emitter, message, error);
                turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            },
            () -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                AiMessage assistant = turnFinisher.finish(emitter, sessionId, state, durationMs, messageId, route);
                turnFinisher.finishRoute(route, assistant == null ? null : assistant.getId(),
                    route.degraded ? AiRouteRun.STATUS_DEGRADED : AiRouteRun.STATUS_SUCCESS, null, durationMs);
                // 非 HITL 恢复段（指定专家兜底）没有暂存可清，故按 messageId 判空
                if (messageId != null) {
                    pendingStore.remove(sessionId, messageId);
                    pendingStore.release(sessionId, messageId);
                }
            });
        // SSE 断连 / 超时：取消后端执行（与对话页「停止」同一路径）
        emitter.onCompletion(run::cancel);
        emitter.onTimeout(run::cancel);
        emitter.onError(ignored -> run.cancel());
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
    void drivePinnedFallback(HarnessAgent agent, RuntimeContext ctx, AiSession session,
                             AiRouteContext route, AiStreamState state, long startNanos,
                             SseEmitter emitter) {
        Long sessionId = session.getId();
        // 先钉死审计动作：后续 finishRoute 见 action 非空即不再兜底成 self
        route.action = AiRouteRun.ACTION_PINNED;
        route.decisionJson = pinnedFallbackDecision(route);
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_ANSWER_RESET));
        state.answer.setLength(0);
        // 兜底编排同为阻塞式调用：登记「dispose 该 Mono」为取消句柄
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
        // 收尾幂等：外层 dispose（doOnCancel）、内层专家被中断（route.cancelled）、异常三者只会生效一个
        AtomicBoolean settled = new AtomicBoolean(false);
        Disposable subscription = Mono.fromCallable(() -> routeOrchestrator.executePinned(route))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnCancel(() -> {
                // 用户停止 / SSE 断连：本段无 agent 事件回调可依，取消时就地按「已中止」收尾
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                traceService.appendTransition(route.traceId, "cancelled", "用户停止指定专家兜底");
                turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, CANCELLED_TEXT, durationMs);
            })
            .doFinally(signal -> runRegistry.unregister(sessionId, cancelable))
            .subscribe(report -> {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                if (route.cancelled) {
                    // 用户停止：专家调用已被中断（编排器置 route.cancelled），按「已中止」收尾（不降级）
                    long cancelledMs = (System.nanoTime() - startNanos) / 1_000_000;
                    turnFinisher.finishCancelled(emitter, sessionId, state, cancelledMs, null, route);
                    return;
                }
                if (route.pendingResume != null) {
                    // 指定专家命中「需确认」：与主路径同款暂停并落确认卡片
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    Long pausedMessageId = turnFinisher.finishPending(emitter, sessionId, state, durationMs, route);
                    turnFinisher.finishRoute(route, pausedMessageId, AiRouteRun.STATUS_PENDING, "等待人工确认", durationMs);
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
                AiMessage assistant = turnFinisher.finish(emitter, sessionId, state, durationMs, null, route);
                turnFinisher.finishRoute(route, assistant == null ? null : assistant.getId(),
                    AiRouteRun.STATUS_SUCCESS, null, durationMs);
            }, error -> {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                String message = AiSseSupport.rootCauseMessage(error);
                log.error("指定专家兜底编排失败，sessionId={}", sessionId, error);
                traceService.appendTransition(route.traceId, "error", "指定专家兜底失败：" + message);
                turnFinisher.failStream(emitter, message, error);
                turnFinisher.finishRoute(route, null, AiRouteRun.STATUS_ABORTED, message, durationMs);
            });
        holder.set(subscription);
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

}
