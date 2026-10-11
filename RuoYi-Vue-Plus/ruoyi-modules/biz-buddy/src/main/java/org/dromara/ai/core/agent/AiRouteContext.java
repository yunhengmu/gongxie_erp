package org.dromara.ai.core.agent;

import io.agentscope.core.message.ContentBlock;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次编排运行的共享状态（REQ-AI-30）。
 *
 * <p>由 {@link AiChatFacade} 在入口智能体（{@code ai_entry_agent}）的一轮对话开始时建立，
 * 随 {@code RuntimeContext} 传给 {@link RouteDecisionTool}；工具在编排过程中读写，
 * 收尾由门面落库。字段为**包级可见**：这是「一次编排运行的共享可变状态」，写入方（工具执行线程）
 * 与读取方（收尾，订阅回调线程）分处两个类，故与 {@link AiStreamState} 同款不做样板访问器。</p>
 *
 * <p>没有实例即「当前不在编排链路」：普通智能体对话与无人值守链路（Phase 1 不启用编排）
 * 都不会放置本对象，{@link RouteDecisionTool} 据此回绝并让模型照常作答。</p>
 *
 * @author BizBuddy
 */
@Slf4j
final class AiRouteContext {

    /** 本次编排主键（ai_route_run.id，开始即落库便于中途失败可查） */
    final Long routeRunId;

    /** 用户会话主键 */
    final Long sessionId;

    /** 提问用户 */
    final Long userId;

    /** 提问用户部门（可见范围过滤用；Reactor 线程取不到 LoginHelper，故创建时显式传入） */
    final Long deptId;

    /** 入口智能体主键 */
    final Long entryAgentId;

    /**
     * 指定专家主键（REQ-AI-31，可空）。
     *
     * <p>取自 {@code ai_session.pinned_expert_id}：非空表示**本轮强制路由到该专家**（用户意志覆盖
     * 模型决策），{@link RouteDecisionTool} 据此忽略模型传入的 {@code action} / {@code expert_agent_key}，
     * 且编排侧**不换专家**（{@link AiRouteOrchestrator}）。为空则走既有编排语义。</p>
     */
    volatile Long pinnedExpertId;

    /**
     * 指定专家的候选视图（REQ-AI-33）。
     *
     * <p>{@link #pinnedExpertId} 指向的专家可能 {@code auto_route='0'}——它**不在**自动候选清单
     * {@link #candidates} 内，若 {@link #pinnedCandidate()} 只在清单里找，专家名会回落兜底文案（回归 B2）。
     * 故由 {@link AiChatFacade} 在建立编排上下文时按 {@code visibleExpert(...)} 单独解析后放入本字段，
     * {@link #pinnedCandidate()} 优先返回它。</p>
     */
    volatile AiExpertCandidate pinnedExpert;

    /**
     * 指定专家的 token 消耗来源标记恒为 {@code user}（用户指定），仅用于审计留档。
     */
    volatile String pinSource;

    /** 候选专家清单（每轮现算，注入路由提示词） */
    final List<AiExpertCandidate> candidates;

    /** 候选专家索引（key → 候选，服务端强校验 expert_agent_key 用） */
    final Map<String, AiExpertCandidate> candidateByKey;

    /** 编排链路主键（专家调用作为其子链路） */
    final Long traceId;

    /**
     * 会话级资源集（REQ-AI-32）：专家装配**继承**同一份资源集（不再 {@code pack=null}）。
     *
     * <p>由 {@link AiChatFacade} 在建立编排上下文时放入（每轮现读），专家调用据此装配出与小Z 同源的
     * 工具 / 技能 / MCP 工具可见面（专家侧仅保留只读 + 会话显式选中的只读工具）。</p>
     */
    AiSessionResourceSet resources = AiSessionResourceSet.empty();

    /**
     * 本轮用户消息携带的附件内容块（文档纯文本块 + 图片块）。
     *
     * <p>专家用独立会话、拿不到用户会话历史，若只下发任务片段文本，专家就**看不到**用户上传的图片与文档；
     * 故由 {@link AiChatFacade} 在建立编排上下文时把同一批内容块放入本字段，
     * {@link AiRouteOrchestrator} 下发给专家时与任务片段一并携带。为空表示本轮无附件。</p>
     */
    volatile List<ContentBlock> attachmentBlocks = List.of();

    /** SSE 发射器；无人值守链路为 null（只落库、不下发） */
    final SseEmitter emitter;

    /**
     * 是否为「无人值守」编排（定时任务 / 渠道接入）。
     *
     * <p>无人值守链路**没有人类可确认**：命中「需确认」的写工具不能走框架 HITL 暂停（否则会悬挂），
     * 故 {@link AiRouteOrchestrator} 对专家侧一律按「拒绝」处理（无视 {@code expert_write_enabled}）。</p>
     */
    volatile boolean unattended;

    /** 编排开始时间（纳秒，用于统计总耗时） */
    final long startedNanos = System.nanoTime();

    /** 专家调用次数（含重试与换专家） */
    final AtomicInteger expertCallCount = new AtomicInteger();

    /**
     * 专家轮 Token 累计（输入 + 输出，REQ-AI-30 Phase 2 第四项）。
     *
     * <p>用于「编排预算」判定：每次专家调用结束后累计（与 {@code ai_route_expert_call} 落库同源），
     * 触顶即不再发起重试 / 换专家。只覆盖**专家轮**：入口轮 Token 由门面统计，编排器看不到。</p>
     */
    final AtomicLong expertPromptTokens = new AtomicLong();

    /** 专家轮输出 Token 累计（口径同上） */
    final AtomicLong expertCompletionTokens = new AtomicLong();

    /**
     * 是否已超出编排预算（Token 或墙钟）。
     *
     * <p>置位后编排器**不再发起新的专家调用**（重试 / 换专家都停），
     * 按降级处理并如实声明超限原因。</p>
     */
    volatile boolean budgetExceeded;

    /**
     * 专家调用产生的工具调用明细（REQ-AI-30 Phase 2）。
     *
     * <p>编排期助手消息主键尚未产生，故先在此累积（专家执行线程写入、收尾线程读取），
     * 由 {@link AiTurnFinisher} 在收尾落库助手消息时与入口轮明细**合并一次写入**，
     * 使专家执行的（写）操作可审计、可归属到具体专家。</p>
     */
    final Queue<AiToolCallLog> expertToolLogs = new ConcurrentLinkedQueue<>();

    /** 决策动作（self 自答 / route 路由） */
    volatile String action;

    /** 决策原文（JSON，含 reason / confidence） */
    volatile String decisionJson;

    /** 下发给专家的任务片段（原样留档） */
    volatile String taskContext;

    /** 是否调用过路由决策工具（未调用＝入口智能体自答） */
    volatile boolean routeInvoked;

    /**
     * 是否为 HITL 恢复段（REQ-AI-30 Phase 2 修复）。
     *
     * <p>恢复段会**新建**编排上下文（复用同一次编排的 {@code routeRunId}），此时 {@link #action}
     * 与 {@link #decisionJson} 是空的——二者只在首段的 {@code route_decision} 工具里赋值。
     * 若收尾仍按「未调用决策工具即自答」兜底，会把首段写入的 {@code route} 覆盖成 {@code self}、
     * 把决策原文覆盖成 NULL（实测缺陷）。故以此为标记跳过兜底，并由
     * {@code AiRouteRecorder#finishRun} 对这三列做「非空才更新」。</p>
     */
    volatile boolean resumed;

    /** 是否已降级（未拿到合格专家结论） */
    volatile boolean degraded;

    /**
     * 是否处于「专家直答」接管态（REQ-AI-30 直答修订，2026-10-03）。
     *
     * <p>为真表示：专家的回复**直接呈现给用户**，入口智能体（小Z）本轮不再输出正文——
     * 门面据此**丢弃入口轮的正文增量**（{@code TextBlockDeltaEvent}），收尾落库取
     * {@link #handoffAnswer}。专家调用失败/超时降级时会被**置回 false**，让小Z 兜底自答。</p>
     */
    volatile boolean handoff;

    /**
     * 专家直答的完整正文（跨 HITL 暂停累积）。
     *
     * <p>专家轮以流式直传用户，但落库需要一份完整文本；暂停-恢复场景下续跑会新建专家会话状态，
     * 故由编排器在每次**成功**的专家调用结束时写入本缓冲，收尾落库取它而非入口轮正文。</p>
     */
    final StringBuilder handoffAnswer = new StringBuilder();

    /** 降级或中止原因 */
    volatile String degradeReason;

    /** 本轮助手消息主键（收尾落库时回填） */
    volatile Long messageId;

    /**
     * 入口轮（或编排恢复段）的流式状态引用（REQ-AI-30 Phase 2）。
     *
     * <p>专家命中「需确认」时，框架暂停的是**专家自己的会话**，而用户看到的是入口轮——
     * 编排器据此把专家侧的待确认信息**上浮**写入入口轮状态，由门面的收尾逻辑落「待确认」消息并下发确认卡片。</p>
     */
    volatile AiStreamState entryState;

    /**
     * 专家续跑信息：非空表示本轮编排正等待人工确认（恢复段据此驱动专家续跑）。
     */
    volatile AiConfirmPendingStore.ExpertResume pendingResume;

    /**
     * 是否为「整合轮」：恢复段拿到专家结论后再驱动入口智能体整合时置位，
     * {@link RouteDecisionTool} 据此**直接回绝**再路由（服务端强约束，不靠提示词）。
     */
    volatile boolean routeDone;

    /**
     * 构造一次编排运行状态。
     *
     * @param routeRunId     编排主键
     * @param sessionId      会话主键
     * @param userId         提问用户
     * @param deptId         提问用户部门
     * @param entryAgentId   入口智能体主键
     * @param candidates     候选专家清单
     * @param traceId        编排链路主键
     * @param emitter        SSE 发射器（可空）
     */
    AiRouteContext(Long routeRunId, Long sessionId, Long userId, Long deptId, Long entryAgentId,
                   List<AiExpertCandidate> candidates, Long traceId, SseEmitter emitter) {
        this.routeRunId = routeRunId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.deptId = deptId;
        this.entryAgentId = entryAgentId;
        this.candidates = candidates;
        this.candidateByKey = new java.util.LinkedHashMap<>();
        for (AiExpertCandidate candidate : candidates) {
            if (candidate.agentKey() != null) {
                this.candidateByKey.put(candidate.agentKey(), candidate);
            }
        }
        this.traceId = traceId;
        this.emitter = emitter;
    }

    /**
     * 按 key 取候选专家（服务端强校验：模型只能选清单里的）。
     *
     * @param agentKey 专家标识
     * @return 候选专家；不在清单中时返回 null
     */
    AiExpertCandidate candidate(String agentKey) {
        return agentKey == null ? null : candidateByKey.get(agentKey);
    }

    /**
     * 按主键取指定专家（REQ-AI-31）：用户指定的专家。
     *
     * <p>用于把「已指定专家」提示注入路由提示词；{@code auto_route='0'} 的专家不在自动候选清单内，
     * 但由门面解析后放入 {@link #pinnedExpert}，故**优先返回它**。清单里也没有（可见范围变化导致不再可见）
     * 时返回 null，由服务端强制编排按主键解析并可能降级，**不依赖**本方法的结果。</p>
     *
     * @return 指定专家候选；未指定或不可见时返回 null
     */
    AiExpertCandidate pinnedCandidate() {
        if (pinnedExpert != null) {
            return pinnedExpert;
        }
        if (pinnedExpertId == null) {
            return null;
        }
        for (AiExpertCandidate candidate : candidates) {
            if (pinnedExpertId.equals(candidate.agentId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 渲染候选清单段落（注入路由提示词）。
     *
     * @return 形如多行 {@code - key=… | 名称=…} 的文本；无候选时返回固定说明
     */
    String renderCandidates() {
        if (candidates.isEmpty()) {
            return "（当前没有任何可用的专家，请自行回答用户的问题。）";
        }
        StringBuilder sb = new StringBuilder();
        for (AiExpertCandidate candidate : candidates) {
            sb.append(candidate.render()).append('\n');
        }
        return sb.toString();
    }
}
