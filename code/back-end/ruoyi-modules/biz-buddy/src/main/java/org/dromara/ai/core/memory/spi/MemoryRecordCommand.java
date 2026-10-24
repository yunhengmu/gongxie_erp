package org.dromara.ai.core.memory.spi;

/**
 * 记忆写入命令（docs/24 §5.10）。
 *
 * <p>身份字段必须齐备，外部记忆后端才能按用户 / 智能体 / 会话做隔离。</p>
 *
 * @param userId    记忆归属用户主键
 * @param agentId   智能体主键（为空时由实现方取入口智能体）
 * @param sessionId 会话标识（可为空，表示与具体会话无关的长期事实）
 * @param text      待写入事实（调用方需保证已净化）
 * @author BizBuddy
 */
public record MemoryRecordCommand(Long userId, Long agentId, String sessionId, String text) {
}
