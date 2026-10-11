package org.dromara.ai.core.agent;

import io.agentscope.core.message.ToolResultState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的收尾落库与留痕（REQ-AI-30 / REQ-AI-28）。
 *
 * <p>从 {@link AiChatFacade} 抽出的收尾逻辑集合：正常收尾 / 取消收尾 / 待确认暂停收尾、
 * 决策日志留痕、工具明细构造与失败信封下发。写入方是对话订阅回调线程（或专家续跑线程），
 * 读取方是同一段收尾逻辑，故与 {@link AiStreamState} 同款不做跨线程拆分。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTurnFinisher {

    /**
     * 事件类型：本轮结束
     */
    private static final String EVENT_DONE = "done";

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

    private final IAiSessionService sessionService;

    private final IAiPermissionDecisionService decisionService;

    private final IAiUsageStatService usageStatService;

    private final IAiTraceService traceService;

    private final IAiMemoryService memoryService;

    private final AiProperties aiProperties;

    /**
     * 编排链路落库（REQ-AI-30）：一次编排一行 + 每次专家调用一行
     */
    private final AiRouteRecorder routeRecorder;

    private final AiConfirmPendingStore pendingStore;

    /**
     * 事件桥：收尾前冲刷安全尾部缓冲，保证「落库内容 == 前端所见」
     */
    private final AiStreamEventBridge streamBridge;

    /**
     * 安全合规守卫（输出审核 + PII 脱敏）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * 安全事件留痕
     */
    private final IAiSafetyEventService safetyEventService;

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
    void finishRoute(AiRouteContext route, Long messageId, String status,
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
     * 取消收尾（用户停止 / SSE 断连）：按「已中止」落库并留痕，关闭流。
     *
     * <p>与正常收尾的区别只在状态：消息状态与链路、编排运行终态都取「已中止」，且不再下发错误提示——
     * 用户主动停止不是失败。已流出的部分正文照常保留，便于用户回看。</p>
     *
     * @param emitter         SSE 发射器；无人值守链路传 null
     * @param sessionId       会话主键
     * @param state           流式累积状态
     * @param durationMs      本次耗时（毫秒）
     * @param resumeMessageId 恢复段对应的助手消息主键；非空时追加更新同一条消息
     * @param route           编排运行状态（可为 null）
     */
    void finishCancelled(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                         Long resumeMessageId, AiRouteContext route) {
        streamBridge.flushSafety(emitter, state);
        traceService.appendTransition(state.traceId, "cancelled", CANCELLED_TEXT);
        traceService.accumulate(state.traceId, AiTrace.STATUS_ABORTED, durationMs, null,
            null, null, null, CANCELLED_TEXT);
        try {
            if (resumeMessageId == null) {
                sessionService.persistAssistantAborted(sessionId, state.answer.toString(), durationMs, state.sources);
            } else {
                sessionService.appendAssistantTurn(resumeMessageId, "",
                    AiMessage.STATUS_ABORTED, durationMs, buildDoneToolLogs(state, route), state.toTokenUsage(),
                    state.sources);
            }
        } catch (Exception e) {
            log.error("落库已停止消息异常，sessionId={}, messageId={}", sessionId, resumeMessageId, e);
        }
        finishRoute(route, resumeMessageId, AiRouteRun.STATUS_ABORTED, CANCELLED_TEXT, durationMs);
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_ABORTED));
        AiSseSupport.complete(emitter);
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
    AiMessage finish(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                     Long resumeMessageId, AiRouteContext route) {
        // 安全合规：先冲刷尾部缓冲（与 SSE 同源），再对最终正文做一次完整守卫（含专家直答正文）
        streamBridge.flushSafety(emitter, state);
        List<AiToolCallLog> logs = buildToolLogs(state, route);
        String replyId = state.replyId;
        ChatTokenUsage tokens = state.toTokenUsage();
        // 专家直答接管态（REQ-AI-30 直答修订）：回答正文取专家完整正文，而非入口轮（已被丢弃的）正文
        String content = route != null && route.handoff && route.handoffAnswer.length() > 0
            ? route.handoffAnswer.toString() : state.answer.toString();
        SafetyDecision outputGuard = safetyGuard.guard(content, SafetyScene.OUTPUT);
        if (outputGuard.hit()) {
            safetyEventService.record(SafetyScene.OUTPUT, outputGuard, safetyGuard.engineName(),
                sessionId, null, state.agentId, state.userId, content);
        }
        if (outputGuard.blocked()) {
            content = AiSafetyGuard.REFUSAL_TEXT;
        } else if (outputGuard.text() != null) {
            content = outputGuard.text();
        }
        AiMessage assistant;
        try {
            if (resumeMessageId == null) {
                assistant = sessionService.persistAssistantTurn(sessionId, content,
                    replyId, durationMs, logs, tokens, state.userId, state.sources);
            } else {
                assistant = sessionService.appendAssistantTurn(resumeMessageId, content,
                    AiMessage.STATUS_NORMAL, durationMs, logs, tokens, state.sources);
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
        if (!state.sources.isEmpty()) {
            // 参考来源：与 references 事件同一 VO 序列化，前端刷新前也可据此回填
            payload.put("references", state.sources);
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
    Long finishPending(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                       AiRouteContext route) {
        streamBridge.flushSafety(emitter, state);
        AiConfirmPendingStore.ExpertResume expertResume = route == null ? null : route.pendingResume;
        // 专家链路的暂停：正文用**平台文案**，不落入口智能体在暂停后可能续写的残句
        String content = expertResume == null ? state.answer.toString() : PENDING_EXPERT_TEXT;
        AiMessage assistant;
        try {
            // 本段已完成（含被拒）的工具调用照常落明细：暂停发生在工具首次请求之后，
            // 之前已执行的读/内置工具不应因为「本轮暂停」而从审计里消失
            assistant = sessionService.persistPendingTurn(sessionId, content, state.pendingReplyId, durationMs,
                buildDoneToolLogs(state, route), state.toTokenUsage(), state.userId, state.sources);
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
    void finishPendingResume(SseEmitter emitter, Long sessionId, AiStreamState state, long durationMs,
                             Long messageId, AiRouteContext route) {
        streamBridge.flushSafety(emitter, state);
        AiConfirmPendingStore.ExpertResume expertResume = route == null ? null : route.pendingResume;
        // 专家链路的暂停文案在首次暂停时已写入同一条消息，再次暂停不再重复追加（空串＝只回写状态）
        String content = expertResume == null ? state.answer.toString() : "";
        AiMessage assistant;
        try {
            // 同上：暂停前已完成的工具调用（如用户刚确认并已执行的写操作）必须落行，
            // 否则恢复段换了新的编排上下文，这些明细就丢了
            assistant = sessionService.appendAssistantTurn(messageId, content, AiMessage.STATUS_PENDING_CONFIRM,
                durationMs, buildDoneToolLogs(state, route), state.toTokenUsage(), state.sources);
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
     * <p>安全合规（TOOL_RESULT）：工具结果落库前统一过守卫脱敏，只脱敏不阻断。</p>
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
        for (AiToolCallLog log : logs) {
            log.setToolOutput(safetyGuard.mask(log.getToolOutput(), SafetyScene.TOOL_RESULT));
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
     * 下发失败事件并终止 SSE 流（错误信封的唯一构造点）。
     *
     * @param emitter SSE 发射器；为 null 时（无人值守链路）只丢弃 SSE 动作
     * @param message 失败说明（已取根因）
     * @param error   原始异常，用于终止流并触发前端错误回调
     */
    void failStream(SseEmitter emitter, String message, Throwable error) {
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_ERROR);
        payload.put("message", message);
        AiSseSupport.send(emitter, payload);
        if (emitter != null) {
            emitter.completeWithError(error);
        }
    }

}
