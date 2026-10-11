package org.dromara.ai.core.agent;

import io.agentscope.core.model.ChatUsage;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.common.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单轮对话的流式累积状态（由事件桥写入，由 {@link AiTurnFinisher} 的收尾逻辑读取）。
 *
 * <p>字段为**包级可见**：这些是「一次对话运行的共享可变状态」，写入方（{@link AiStreamEventBridge}，
 * Reactor 订阅线程）与读取方（收尾落库，同一订阅回调线程）分处两个类，故不再用私有字段 + 访问器
 * （会引入数十个样板方法且不改变线程可见性要求）。跨线程可见性由 {@code volatile} 保证，
 * 因此**不要**把同一轮的状态拆到多个实例里。</p>
 *
 * @author BizBuddy
 */
final class AiStreamState {

    /** 回答正文 */
    final StringBuilder answer = new StringBuilder();

    /**
     * 安全守卫的未下发尾部缓冲（跨片命中防切断）：内容安全开启时，正文先入此缓冲，
     * 仅下发「安全的头部」，尾部保留待下一次拼接后再扫描；收尾由 {@code AiTurnFinisher} 冲刷。
     */
    final StringBuilder safetyPending = new StringBuilder();

    /**
     * 本轮是否已被安全拦截（命中 BLOCK）：为真时后续正文一律丢弃，最终以拒答文案落库
     */
    volatile boolean safetyBlocked;

    /** 工具调用累积（toolCallId → 累积项） */
    final Map<String, AiToolCallAcc> tools = new ConcurrentHashMap<>();

    /** AgentScope replyId */
    volatile String replyId;

    /** 输入 Token 累计 */
    int promptTokens;

    /** 输出 Token 累计 */
    int completionTokens;

    /** 命中缓存的 Token 累计（仅记录，不计入总量） */
    int cachedTokens;

    /** 会话主键 */
    Long sessionId;

    /** 触发用户 */
    Long userId;

    /** 智能体主键 */
    Long agentId;

    /** 链路追踪主键（REQ-AI-28） */
    Long traceId;

    /** 对话模型主键 */
    Long modelId;

    /** 是否因等待人工确认而暂停 */
    volatile boolean pendingConfirm;

    /**
     * 发起待确认工具调用的智能体主键（REQ-AI-30 Phase 2）。
     *
     * <p>{@code null} 表示这是**入口智能体自己**的暂停；非空表示暂停源自**专家调用**
     * （编排链路的 HITL 贯通）：此时待确认卡片与决策留痕都应归属到该专家。</p>
     */
    volatile Long pendingAgentId;

    /** 暂停时的 AgentScope replyId */
    volatile String pendingReplyId;

    /** 待确认的工具调用 */
    List<AiConfirmPendingStore.PendingToolCall> pendingToolCalls = List.of();

    /**
     * 汇总本轮 Token 用量。
     *
     * @return Token 用量；模型未返回用量时为空
     */
    ChatTokenUsage toTokenUsage() {
        if (promptTokens == 0 && completionTokens == 0) {
            return null;
        }
        return new ChatTokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    /**
     * 累加一次模型调用的用量（一轮对话可能触发多次模型调用）。
     *
     * @param usage 本次模型调用用量；为空时忽略
     */
    void accumulateUsage(ChatUsage usage) {
        if (usage != null) {
            promptTokens += usage.getInputTokens();
            completionTokens += usage.getOutputTokens();
            cachedTokens += usage.getCachedTokens();
        }
    }

    /**
     * 由累积状态构造工具调用明细（不含会话 / 消息主键，由落库方补齐）。
     *
     * <p>「未获批的写工具尚未执行、不计入明细」由调用路径保证：暂停收尾
     * （{@code finishPending} / {@code finishPendingResume}）传 null 明细，不经本方法；
     * 恢复段执行的是用户已确认的工具，正常计次。</p>
     *
     * <p>入口轮与专家调用共用本方法：专家侧由 {@link AiRouteOrchestrator} 传入**专家自己的**
     * agentId，使工具调用可归属到具体智能体（REQ-AI-30 Phase 2）。</p>
     *
     * @param previewChars 结果摘要的字符上限
     * @param agentId      发起该工具调用的智能体主键（可空）
     * @return 工具调用明细列表
     */
    List<AiToolCallLog> toToolLogs(int previewChars, Long agentId) {
        List<AiToolCallLog> logs = new ArrayList<>();
        for (Map.Entry<String, AiToolCallAcc> entry : tools.entrySet()) {
            AiToolCallAcc acc = entry.getValue();
            AiToolCallLog entity = new AiToolCallLog();
            entity.setToolCallId(entry.getKey());
            entity.setToolName(StringUtils.isBlank(acc.toolName) ? "unknown" : acc.toolName);
            entity.setToolInput(acc.input.toString());
            entity.setToolOutput(AiSseSupport.abbreviate(acc.output.toString(), previewChars));
            entity.setState(acc.state == null ? null : acc.state.name());
            entity.setDurationMs(acc.durationMs);
            entity.setAgentId(agentId);
            logs.add(entity);
        }
        return logs;
    }
}
