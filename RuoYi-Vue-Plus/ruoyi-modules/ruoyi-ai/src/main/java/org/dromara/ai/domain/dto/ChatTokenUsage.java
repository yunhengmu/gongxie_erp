package org.dromara.ai.domain.dto;

/**
 * 单轮对话的 Token 用量。
 *
 * <p>一轮对话可能包含多次模型调用（工具调用循环），此处为**累加后**的汇总值。</p>
 *
 * @param promptTokens     输入 Token 数
 * @param completionTokens 输出 Token 数
 * @param totalTokens      总 Token 数
 * @author BizBuddy
 */
public record ChatTokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
