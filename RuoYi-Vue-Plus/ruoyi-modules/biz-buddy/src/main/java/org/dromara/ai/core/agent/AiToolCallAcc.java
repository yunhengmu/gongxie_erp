package org.dromara.ai.core.agent;

import io.agentscope.core.message.ToolResultState;

/**
 * 单次工具调用的累积信息（属于 {@link AiStreamState} 的一部分）。
 *
 * <p>字段为**包级可见**，理由与 {@link AiStreamState} 相同：写入方是事件桥，读取方是收尾落库。</p>
 *
 * @author BizBuddy
 */
final class AiToolCallAcc {

    /** 工具名称 */
    volatile String toolName;

    /** 入参 JSON 累积 */
    final StringBuilder input = new StringBuilder();

    /** 结果文本累积 */
    final StringBuilder output = new StringBuilder();

    /** 结果状态 */
    volatile ToolResultState state;

    /** 耗时（毫秒） */
    volatile Long durationMs;

    /** 事件来源路径（子智能体调用时形如 {@code main/<子智能体标识>}，主智能体为 null） */
    volatile String source;

    /** 开始时间（纳秒） */
    volatile long startNanos;
}
