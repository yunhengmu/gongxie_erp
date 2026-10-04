package org.dromara.ai.domain.dto;

/**
 * 无人值守执行一轮对话的结果（定时任务链路）。
 *
 * <p>与对话工作台不同：无人值守链路没有前端，不产生 SSE 事件，
 * 由调用方（定时任务）自行把结果写入执行记录。</p>
 *
 * @param sessionId        会话主键
 * @param messageId        落库的助手消息主键（执行失败到未落库时为 null）
 * @param success          是否成功
 * @param errorMsg         失败原因（成功时为 null）
 * @param durationMs       本轮耗时（毫秒）
 * @param toolCallCount    工具调用次数
 * @param promptTokens     输入 Token 数
 * @param completionTokens 输出 Token 数
 * @param totalTokens      总 Token 数
 * @author BizBuddy
 */
public record AiTaskExecution(Long sessionId, Long messageId, boolean success, String errorMsg,
                              Long durationMs, Integer toolCallCount,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens) {

}
