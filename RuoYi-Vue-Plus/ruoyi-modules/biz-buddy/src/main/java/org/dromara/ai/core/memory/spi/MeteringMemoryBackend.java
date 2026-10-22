package org.dromara.ai.core.memory.spi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.service.IAiUsageStatService;

/**
 * 计量装饰器：把记忆写入折算为 Token 用量计入 {@code ai_usage_stat}。
 *
 * <p>框架内部调用没有 usage 回调，这里按「1 Token ≈ 4 字符」估算，至少让记忆成本可见；
 * 计量失败只告警，不影响记忆写入。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
public class MeteringMemoryBackend implements AiMemoryBackend {

    /**
     * Token 与字符的粗略折算比例
     */
    private static final int CHARS_PER_TOKEN = 4;

    private final AiMemoryBackend delegate;

    private final IAiUsageStatService usageStatService;

    @Override
    public void record(MemoryRecordCommand command) {
        delegate.record(command);
        if (command == null || command.userId() == null || command.text() == null) {
            return;
        }
        int tokens = Math.max(1, command.text().length() / CHARS_PER_TOKEN);
        try {
            usageStatService.accumulate(command.userId(), command.agentId(), null,
                new ChatTokenUsage(tokens, 0, tokens), 0, 1);
        } catch (Exception e) {
            log.warn("记忆写入用量统计失败：userId={}, err={}", command.userId(), e.getMessage());
        }
    }

    @Override
    public String retrieve(MemoryQuery query) {
        return delegate.retrieve(query);
    }

    @Override
    public void delete(long userId) {
        delegate.delete(userId);
    }

    @Override
    public MemoryBackendStats stats(long userId) {
        return delegate.stats(userId);
    }
}
