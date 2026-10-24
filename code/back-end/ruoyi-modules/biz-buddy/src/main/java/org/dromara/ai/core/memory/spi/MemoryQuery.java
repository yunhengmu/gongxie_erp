package org.dromara.ai.core.memory.spi;

/**
 * 记忆查询条件（docs/24 §5.10）。
 *
 * @param userId    记忆归属用户主键
 * @param sessionId 会话标识（可为空）
 * @param maxChars  返回文本的字符预算（避免超长记忆挤占上下文）
 * @author BizBuddy
 */
public record MemoryQuery(Long userId, String sessionId, int maxChars) {
}
