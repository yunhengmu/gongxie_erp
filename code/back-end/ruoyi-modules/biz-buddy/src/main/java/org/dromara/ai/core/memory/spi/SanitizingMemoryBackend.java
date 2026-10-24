package org.dromara.ai.core.memory.spi;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.memory.AiMemorySanitizer;
import org.dromara.common.core.utils.StringUtils;

/**
 * 净化装饰器：写入前做 PII / 指令特征过滤，读取后二次过滤。
 *
 * <p>治理边界必须在平台侧，外部记忆平台（mem0 / ReMe）不负责企业合规，因此无论后端换成什么实现，
 * 这一层都要保留（docs/24 §5.10）。</p>
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
public class SanitizingMemoryBackend implements AiMemoryBackend {

    private final AiMemoryBackend delegate;

    private final AiMemorySanitizer sanitizer;

    @Override
    public void record(MemoryRecordCommand command) {
        if (command == null) {
            return;
        }
        AiMemorySanitizer.SanitizeResult sanitized = sanitizer.sanitizeEntry(command.text());
        if (StringUtils.isBlank(sanitized.content())) {
            // 命中密钥或指令特征：整条丢弃，不进入长期记忆
            return;
        }
        delegate.record(new MemoryRecordCommand(command.userId(), command.agentId(),
            command.sessionId(), sanitized.content()));
    }

    @Override
    public String retrieve(MemoryQuery query) {
        String text = delegate.retrieve(query);
        if (StringUtils.isBlank(text)) {
            return "";
        }
        // 读取后二次过滤：历史遗留的未净化内容也可能被注入，这里再兜一层
        return sanitizer.sanitizeLedger(text).content();
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
