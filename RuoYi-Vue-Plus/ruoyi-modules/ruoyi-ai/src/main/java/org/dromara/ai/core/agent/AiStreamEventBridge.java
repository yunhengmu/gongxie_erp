package org.dromara.ai.core.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Map;

/**
 * AgentScope 事件 → 流式状态与 SSE 信封的桥接器。
 *
 * <p>把 {@code AgentEvent} 归一化为前端协议（{@code text_delta} / {@code tool_call_start} /
 * {@code tool_call_delta} / {@code tool_result}），同时把工具调用累积进 {@link AiStreamState}
 * 并写入链路追踪节点。</p>
 *
 * <p><b>线程约束</b>：本类无状态（仅注入依赖），但访问的 {@link AiStreamState} 是有状态的。
 * 同一轮对话的所有事件必须由**同一个订阅回调线程**依次交给 {@link #handle}，且收尾读取同一实例；
 * 调用方不得在事件流上引入线程切换（{@code subscribeOn} / {@code publishOn}），否则会丢失内存可见性。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiStreamEventBridge {

    /**
     * 链路追踪（REQ-AI-28）：记录执行期模型版本 / 上下文清单 / 状态迁移
     */
    private final IAiTraceService traceService;

    private final AiProperties aiProperties;

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
     * 处理一个 AgentScope 事件：按事件类型分发（工具调用事件照常下发）。
     *
     * @param emitter SSE 发射器；为 null 时（无人值守链路）只累积状态、不下发
     * @param state   流式累积状态
     * @param event   AgentScope 事件
     */
    public void handle(SseEmitter emitter, AiStreamState state, AgentEvent event) {
        handle(emitter, state, event, true);
    }

    /**
     * 处理一个 AgentScope 事件：按事件类型分发。
     *
     * @param emitter           SSE 发射器；为 null 时（无人值守链路）只累积状态、不下发
     * @param state             流式累积状态
     * @param event             AgentScope 事件
     * @param forwardToolEvents 是否把工具调用事件下发前端。专家直答链路传 {@code false}——
     *                          专家正文直传用户，但其内部工具调用卡片不下发（避免噪声），
     *                          仍照常累积进状态供落库与链路追踪
     */
    public void handle(SseEmitter emitter, AiStreamState state, AgentEvent event, boolean forwardToolEvents) {
        if (event instanceof RequireUserConfirmEvent confirmEvent) {
            onRequireConfirm(state, confirmEvent);
            return;
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            onTextDelta(emitter, state, textDelta);
            return;
        }
        if (event instanceof ToolCallStartEvent start) {
            onToolCallStart(emitter, state, start, forwardToolEvents);
            return;
        }
        if (event instanceof ToolCallDeltaEvent deltaEvent) {
            onToolCallDelta(emitter, state, deltaEvent, forwardToolEvents);
            return;
        }
        if (event instanceof ModelCallEndEvent modelCallEnd) {
            state.accumulateUsage(modelCallEnd.getUsage());
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent resultDelta) {
            onToolResultDelta(state, resultDelta);
            return;
        }
        if (event instanceof ToolResultEndEvent resultEnd) {
            onToolResultEnd(emitter, state, resultEnd, forwardToolEvents);
            return;
        }
        log.debug("忽略的 AgentScope 事件：{}", event == null ? null : event.getClass().getSimpleName());
    }

    /**
     * 工具被配置为「需人工确认」：AgentScope 暂停执行，等待前端确认后恢复。
     *
     * @param state        流式累积状态
     * @param confirmEvent 待确认事件（携带原始 ToolUseBlock，恢复时必须原样复用）
     */
    private void onRequireConfirm(AiStreamState state, RequireUserConfirmEvent confirmEvent) {
        state.pendingConfirm = true;
        state.pendingReplyId = confirmEvent.getReplyId();
        state.pendingToolCalls = new ArrayList<>();
        if (confirmEvent.getToolCalls() != null) {
            for (ToolUseBlock block : confirmEvent.getToolCalls()) {
                state.pendingToolCalls.add(new AiConfirmPendingStore.PendingToolCall(
                    block.getId(), block.getName(), block.getInput(), block.getContent()));
            }
        }
    }

    /**
     * 正文增量：累积正文并下发正文增量事件。
     *
     * @param emitter   SSE 发射器
     * @param state     流式累积状态
     * @param textDelta 正文增量事件
     */
    private void onTextDelta(SseEmitter emitter, AiStreamState state, TextBlockDeltaEvent textDelta) {
        String delta = textDelta.getDelta();
        if (StringUtils.isBlank(delta)) {
            return;
        }
        state.answer.append(delta);
        captureReplyId(state, textDelta.getReplyId());
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TEXT_DELTA);
        payload.put("replyId", textDelta.getReplyId());
        payload.put("blockId", textDelta.getBlockId());
        payload.put("delta", delta);
        AiSseSupport.send(emitter, payload);
    }

    /**
     * 工具调用开始：登记累积槽位、记录链路追踪节点并下发调用开始事件。
     *
     * @param emitter           SSE 发射器
     * @param state             流式累积状态
     * @param start             工具调用开始事件
     * @param forwardToolEvents 是否下发工具事件（专家直答链路为 false，只累积留证）
     */
    private void onToolCallStart(SseEmitter emitter, AiStreamState state, ToolCallStartEvent start,
                                 boolean forwardToolEvents) {
        AiToolCallAcc acc = state.tools.computeIfAbsent(start.getToolCallId(), id -> new AiToolCallAcc());
        acc.toolName = start.getToolCallName();
        acc.startNanos = System.nanoTime();
        acc.source = start.getSource();
        // 链路追踪（REQ-AI-28）：记录工具调用节点（子智能体发起的调用带来源）
        traceService.appendTransition(state.traceId, "tool_call", StringUtils.isBlank(start.getSource())
            ? start.getToolCallName() : start.getToolCallName() + "@" + start.getSource());
        captureReplyId(state, start.getReplyId());
        if (!forwardToolEvents) {
            return;
        }
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TOOL_CALL_START);
        payload.put("replyId", start.getReplyId());
        payload.put("toolCallId", start.getToolCallId());
        payload.put("toolName", start.getToolCallName());
        putSource(payload, start.getSource());
        AiSseSupport.send(emitter, payload);
    }

    /**
     * 工具入参增量：工具入参仅在此事件中增量给出，是「入参可见」的唯一数据来源。
     *
     * @param emitter           SSE 发射器
     * @param state             流式累积状态
     * @param deltaEvent        工具入参增量事件
     * @param forwardToolEvents 是否下发工具事件（专家直答链路为 false，只累积留证）
     */
    private void onToolCallDelta(SseEmitter emitter, AiStreamState state, ToolCallDeltaEvent deltaEvent,
                                 boolean forwardToolEvents) {
        AiToolCallAcc acc = state.tools.computeIfAbsent(deltaEvent.getToolCallId(), id -> new AiToolCallAcc());
        if (StringUtils.isBlank(acc.toolName)) {
            acc.toolName = deltaEvent.getToolCallName();
        }
        if (deltaEvent.getDelta() != null) {
            acc.input.append(deltaEvent.getDelta());
        }
        if (!forwardToolEvents) {
            return;
        }
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TOOL_CALL_DELTA);
        payload.put("toolCallId", deltaEvent.getToolCallId());
        payload.put("delta", deltaEvent.getDelta());
        AiSseSupport.send(emitter, payload);
    }

    /**
     * 工具结果正文增量：仅累积结果文本，结果事件（{@link ToolResultEndEvent}）才下发。
     *
     * @param state       流式累积状态
     * @param resultDelta 工具结果增量事件
     */
    private void onToolResultDelta(AiStreamState state, ToolResultTextDeltaEvent resultDelta) {
        AiToolCallAcc acc = state.tools.computeIfAbsent(resultDelta.getToolCallId(), id -> new AiToolCallAcc());
        if (StringUtils.isBlank(acc.toolName)) {
            acc.toolName = resultDelta.getToolCallName();
        }
        if (resultDelta.getDelta() != null) {
            acc.output.append(resultDelta.getDelta());
        }
    }

    /**
     * 工具结果结束：落状态与耗时、异常节点记链路，并下发工具结果事件。
     *
     * @param emitter           SSE 发射器
     * @param state             流式累积状态
     * @param resultEnd         工具结果结束事件
     * @param forwardToolEvents 是否下发工具事件（专家直答链路为 false，只累积留证）
     */
    private void onToolResultEnd(SseEmitter emitter, AiStreamState state, ToolResultEndEvent resultEnd,
                                 boolean forwardToolEvents) {
        AiToolCallAcc acc = state.tools.computeIfAbsent(resultEnd.getToolCallId(), id -> new AiToolCallAcc());
        if (StringUtils.isBlank(acc.toolName)) {
            acc.toolName = resultEnd.getToolCallName();
        }
        acc.state = resultEnd.getState();
        acc.durationMs = acc.startNanos == 0L ? null : (System.nanoTime() - acc.startNanos) / 1_000_000;
        if (acc.state != null && acc.state != ToolResultState.SUCCESS) {
            // 链路追踪（REQ-AI-28）：只记录失败/拒绝等异常节点，成功节点已在 tool_call 留痕
            traceService.appendTransition(state.traceId, "tool_error",
                acc.toolName + " → " + acc.state.name());
        }
        if (!forwardToolEvents) {
            return;
        }
        String output = AiSseSupport.abbreviate(acc.output.toString(),
            aiProperties.getChat().getToolOutputPreviewChars());
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TOOL_RESULT);
        payload.put("toolCallId", resultEnd.getToolCallId());
        payload.put("toolName", acc.toolName);
        payload.put("state", acc.state == null ? null : acc.state.name());
        payload.put("output", output);
        payload.put("durationMs", acc.durationMs);
        putSource(payload, acc.source);
        AiSseSupport.send(emitter, payload);
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
     * 把事件来源写入 SSE 负载（REQ-AI-04）。
     *
     * <p>同步子智能体的中间事件会带 {@code source}（形如 {@code main/<子智能体标识>}）
     * 实时转发进父流，前端据此把「子智能体徽标」标在工具卡片上；主智能体事件无该字段。</p>
     *
     * @param payload 事件负载
     * @param source  事件来源路径
     */
    private void putSource(Map<String, Object> payload, String source) {
        if (StringUtils.isNotBlank(source)) {
            payload.put("source", source);
        }
    }
}
