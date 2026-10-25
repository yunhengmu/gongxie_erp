package org.dromara.ai.core.memory.spi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 降级装饰器：外部记忆后端不可用时回落「不注入 / 只读摘要」，不阻断对话。
 *
 * <p>读取与统计失败返回空结果（即不注入），写入失败只告警；删除属于合规操作，
 * 失败必须上抛，不能静默吞掉（docs/24 §5.10）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
public class FallbackMemoryBackend implements AiMemoryBackend {

    private final AiMemoryBackend delegate;

    @Override
    public void record(MemoryRecordCommand command) {
        try {
            delegate.record(command);
        } catch (RuntimeException e) {
            log.warn("记忆后端写入失败，已降级为跳过：userId={}, err={}",
                command == null ? null : command.userId(), e.getMessage());
        }
    }

    @Override
    public String retrieve(MemoryQuery query) {
        try {
            return delegate.retrieve(query);
        } catch (RuntimeException e) {
            log.warn("记忆后端读取失败，已降级为不注入：userId={}, err={}",
                query == null ? null : query.userId(), e.getMessage());
            return "";
        }
    }

    @Override
    public void delete(long userId) {
        // 合规删除不允许静默失败
        delegate.delete(userId);
    }

    @Override
    public MemoryBackendStats stats(long userId) {
        try {
            return delegate.stats(userId);
        } catch (RuntimeException e) {
            log.warn("记忆后端统计失败，已降级为空统计：userId={}, err={}", userId, e.getMessage());
            return MemoryBackendStats.empty(userId);
        }
    }
}
