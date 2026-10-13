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
import org.dromara.ai.core.AiRetrievalSourceRegistry;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.vo.AiSourceVo;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * 安全合规守卫（输出审核 + PII 脱敏；带尾部缓冲防跨片切断）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * 安全事件留痕（工具结果命中时按会话 / 消息 / 用户上下文记账）
     */
    private final IAiSafetyEventService safetyEventService;

    /**
     * 检索来源注册表：知识库检索工具命中时暂存的结构化来源，按 toolCallId 取用
     */
    private final AiRetrievalSourceRegistry retrievalSourceRegistry;

    /**
     * SSE 事件：清空本轮已展示正文（安全拦截时丢弃已流出片段）
     */
    private static final String EVENT_ANSWER_RESET = "answer_reset";

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
     * 事件类型：回答参考来源（知识库命中）
     */
    private static final String EVENT_REFERENCES = "references";

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
        captureReplyId(state, textDelta.getReplyId());
        if (state.safetyBlocked) {
            // 已被安全拦截：丢弃后续正文（最终以拒答文案收尾）
            return;
        }
        if (!safetyGuard.anyActive()) {
            // 快速路径：无安全能力时保持原行为（零额外开销）
            state.answer.append(delta);
            emitTextDelta(emitter, state, textDelta, delta);
            return;
        }
        // 尾部缓冲防跨片切断：仅下发「安全头部」，尾部待下一次拼接后再扫描
        state.safetyPending.append(delta);
        int holdBack = Math.max(safetyGuard.holdBackChars(), 0);
        if (state.safetyPending.length() <= holdBack) {
            return;
        }
        // 切点对齐安全边界：避免在 PII 模式（手机号 / 邮箱等连续串）内部切开，否则半截串匹配不到正则、
        // 未脱敏的 head 会被原样下发（实测 2026-10-13：手机号被切成 158/88888/888 三片即明文流出）
        int cut = alignCutToSafeBoundary(state.safetyPending, state.safetyPending.length() - holdBack);
        if (cut <= 0) {
            // 整段都落在可能构成 PII 的连续串内：本次不下发，留待下次一并检测
            return;
        }
        String head = state.safetyPending.substring(0, cut);
        state.safetyPending.delete(0, cut);
        SafetyDecision decision = safetyGuard.guard(head, SafetyScene.OUTPUT);
        recordOutputHit(state, decision);
        if (decision.blocked()) {
            blockAnswer(emitter, state);
            return;
        }
        state.answer.append(decision.text());
        emitTextDelta(emitter, state, textDelta, decision.text());
    }

    /**
     * head 切点向左回退的最大字符数。
     *
     * <p>PII 模式中最长的是银行卡（16~19 位），64 已远超其长度；超过该上限的连续串（如 base64）
     * 不做回退，避免流式正文长时间不下发（这类超长连续串本身也不会被任何 PII 正则匹配）。</p>
     */
    private static final int MAX_CUT_BACKOFF = 64;

    /**
     * 把 head 切点对齐到安全边界，避免在 PII 模式内部切断。
     *
     * <p>脱敏是**逐片**执行的，而 PII 正则（手机号 / 身份证 / 银行卡 / 邮箱 / IP）都要求目标串**连续**：
     * 一旦切点落在串内部，head 只剩半截 → 正则匹配不到 → 未脱敏的 head 会被原样下发给用户
     * （而落库走整段脱敏，故表现为「实时明文、历史掩码」）。</p>
     *
     * <p>做法：若切点两侧都是「可能构成 PII 的字符」，则向左回退到该连续串的起点，让整串留在
     * {@code pending} 里待下次一并检测。</p>
     *
     * @param pending 待扫描缓冲
     * @param cut     原始切点
     * @return 对齐后的切点；为 0 表示本次不下发
     */
    private static int alignCutToSafeBoundary(CharSequence pending, int cut) {
        if (cut <= 0 || cut >= pending.length()) {
            return cut;
        }
        if (!isPiiChar(pending.charAt(cut - 1)) || !isPiiChar(pending.charAt(cut))) {
            // 切点本就在安全边界（如空格 / 中文标点 / 换行）上
            return cut;
        }
        int start = cut;
        while (start > 0 && cut - start < MAX_CUT_BACKOFF && isPiiChar(pending.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    /**
     * 是否「可能构成 PII」的字符：数字 / 字母，以及邮箱与域名中常见的符号。
     *
     * @param c 字符
     * @return 是则返回 true
     */
    private static boolean isPiiChar(char c) {
        return Character.isLetterOrDigit(c) || c == '@' || c == '.' || c == '_' || c == '%'
            || c == '+' || c == '-';
    }

    /**
     * 流式输出命中即留痕（每轮首次命中记一条）。
     *
     * <p>此前 `OUTPUT` 的留痕只在收尾整段过审时补记，而流式层脱敏后**收尾已是掩码文本**、
     * 通常不再命中，于是「实时下发过 PII」在审计里看不出来。此处改为命中当场记一条，
     * 使 {@code ai_safety_event} 能如实反映流式阶段出现过什么。</p>
     *
     * <p>片段取**脱敏后**文本，不把明文 PII 再写进审计库（命中类型由 {@code hits} 体现）；
     * 阻断时决策文本为 null，以统一拒答文案占位。</p>
     *
     * @param state    流式累积状态
     * @param decision 本次守卫决策
     */
    private void recordOutputHit(AiStreamState state, SafetyDecision decision) {
        if (!decision.hit() || state.outputSafetyRecorded) {
            return;
        }
        state.outputSafetyRecorded = true;
        String snippet = decision.text() == null ? AiSafetyGuard.REFUSAL_TEXT : decision.text();
        safetyEventService.record(SafetyScene.OUTPUT, decision, safetyGuard.engineName(),
            state.sessionId, null, state.agentId, state.userId,
            AiSseSupport.abbreviate(snippet, aiProperties.getChat().getToolOutputPreviewChars()));
    }

    /**
     * 下发正文增量事件。
     *
     * @param emitter   SSE 发射器
     * @param state     流式累积状态
     * @param textDelta 原始事件（取 replyId / blockId）
     * @param text      实际下发的正文（已过安全守卫）
     */
    private void emitTextDelta(SseEmitter emitter, AiStreamState state, TextBlockDeltaEvent textDelta, String text) {
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TEXT_DELTA);
        payload.put("replyId", textDelta.getReplyId());
        payload.put("blockId", textDelta.getBlockId());
        payload.put("delta", text);
        AiSseSupport.send(emitter, payload);
    }

    /**
     * 输出命中阻断：清空已展示正文并下发拒答文案，后续正文一律丢弃。
     *
     * @param emitter SSE 发射器
     * @param state   流式累积状态
     */
    private void blockAnswer(SseEmitter emitter, AiStreamState state) {
        state.safetyBlocked = true;
        state.safetyPending.setLength(0);
        state.answer.setLength(0);
        state.answer.append(AiSafetyGuard.REFUSAL_TEXT);
        AiSseSupport.send(emitter, AiSseSupport.envelope(EVENT_ANSWER_RESET));
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TEXT_DELTA);
        payload.put("delta", AiSafetyGuard.REFUSAL_TEXT);
        AiSseSupport.send(emitter, payload);
    }

    /**
     * 冲刷安全尾部缓冲：把未下发的尾部按「输出」场景过守卫后**补发给前端**并入正文（收尾落库前调用）。
     *
     * <p>与 {@link #onTextDelta} 同源，保证「前端所见 == 落库内容」——若只并入正文而不下发，
     * 界面会少掉最后 {@code holdBackChars} 个字（尾部缓冲的残留）。</p>
     *
     * @param emitter SSE 发射器；为 null 时（无人值守链路）只并入正文
     * @param state   流式累积状态
     */
    void flushSafety(SseEmitter emitter, AiStreamState state) {
        if (state.safetyBlocked || state.safetyPending.length() == 0) {
            return;
        }
        String tail = state.safetyPending.toString();
        state.safetyPending.setLength(0);
        SafetyDecision decision = safetyGuard.guard(tail, SafetyScene.OUTPUT);
        recordOutputHit(state, decision);
        if (decision.blocked()) {
            // 与 onTextDelta 同源：清屏 + 下发拒答文案，随后以拒答文案落库
            blockAnswer(emitter, state);
            return;
        }
        state.answer.append(decision.text());
        // 补发未下发的尾部，保证「前端所见 == 落库内容」
        if (StringUtils.isNotBlank(decision.text())) {
            Map<String, Object> payload = AiSseSupport.envelope(EVENT_TEXT_DELTA);
            payload.put("replyId", state.replyId);
            payload.put("delta", decision.text());
            AiSseSupport.send(emitter, payload);
        }
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
        // 参考来源：取出该工具调用暂存的来源并累积（去重），专家直答链路 forwardToolEvents=false 时同样累积
        List<AiSourceVo> sources = retrievalSourceRegistry.drain(resultEnd.getToolCallId());
        List<AiSourceVo> added = mergeSources(state, sources);
        if (!forwardToolEvents) {
            return;
        }
        String output = AiSseSupport.abbreviate(acc.output.toString(),
            aiProperties.getChat().getToolOutputPreviewChars());
        // 安全合规（TOOL_RESULT）：工具结果下发前过守卫；工具结果不允许阻断，BLOCK 时亦以拒答文案替代展示
        SafetyDecision decision = safetyGuard.guard(output, SafetyScene.TOOL_RESULT);
        if (decision.hit()) {
            safetyEventService.record(SafetyScene.TOOL_RESULT, decision, safetyGuard.engineName(),
                state.sessionId, null, state.agentId, state.userId, acc.toolName + "｜" + output);
        }
        Map<String, Object> payload = AiSseSupport.envelope(EVENT_TOOL_RESULT);
        payload.put("toolCallId", resultEnd.getToolCallId());
        payload.put("toolName", acc.toolName);
        payload.put("state", acc.state == null ? null : acc.state.name());
        payload.put("output", decision.blocked() ? AiSafetyGuard.REFUSAL_TEXT : decision.text());
        payload.put("durationMs", acc.durationMs);
        putSource(payload, acc.source);
        AiSseSupport.send(emitter, payload);
        if (!added.isEmpty()) {
            // 新增参考来源事件：载荷结构与 done 载荷的 references 保持一致（同一 VO 序列化）
            Map<String, Object> references = AiSseSupport.envelope(EVENT_REFERENCES);
            references.put("replyId", state.replyId);
            references.put("items", added);
            references.put("toolCallId", resultEnd.getToolCallId());
            AiSseSupport.send(emitter, references);
        }
    }

    /**
     * 把某次检索命中的来源去重后累加进状态，并返回本次新增（或相似度被刷新）的部分。
     *
     * <p>去重口径：{@code docId + chunkIndex} 相同视为同一条，保留最高 score。</p>
     *
     * <p>也供编排器把**专家子状态**的来源回填到入口状态（专家链路使用独立状态）。</p>
     *
     * @param state   流式累积状态
     * @param sources 本次工具命中暂存的来源
     * @return 本次实际新增或刷新得分的来源列表
     */
    public List<AiSourceVo> mergeSources(AiStreamState state, List<AiSourceVo> sources) {
        List<AiSourceVo> added = new ArrayList<>();
        if (state == null || sources == null || sources.isEmpty()) {
            return added;
        }
        for (AiSourceVo source : sources) {
            AiSourceVo existing = findSource(state.sources, source);
            if (existing == null) {
                state.sources.add(source);
                added.add(source);
            } else if (source.getScore() != null
                && (existing.getScore() == null || source.getScore() > existing.getScore())) {
                existing.setScore(source.getScore());
                added.add(existing);
            }
        }
        return added;
    }

    /**
     * 在已累积来源中查找同一条（{@code docId + chunkIndex} 相同）。
     *
     * @param sources 已累积来源
     * @param target  待查找来源
     * @return 命中的已存在项；无则返回 null
     */
    private AiSourceVo findSource(List<AiSourceVo> sources, AiSourceVo target) {
        for (AiSourceVo item : sources) {
            if (Objects.equals(item.getDocId(), target.getDocId())
                && Objects.equals(item.getChunkIndex(), target.getChunkIndex())) {
                return item;
            }
        }
        return null;
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
