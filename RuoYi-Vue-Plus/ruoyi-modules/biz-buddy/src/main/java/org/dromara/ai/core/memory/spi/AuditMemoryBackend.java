package org.dromara.ai.core.memory.spi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.memory.AiMemoryAuditLogger;

import java.util.Map;

/**
 * 审计装饰器：记录写入与删除事件。
 *
 * <p>审计写入失败只告警，不影响记忆主流程（审计日志本身已有该兜底，这里再保证不向调用方抛错）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
public class AuditMemoryBackend implements AiMemoryBackend {

    private final AiMemoryBackend delegate;

    private final AiMemoryAuditLogger auditLogger;

    @Override
    public void record(MemoryRecordCommand command) {
        try {
            delegate.record(command);
            if (command != null) {
                auditLogger.record(command.agentId(), command.userId(),
                    AiMemoryAuditLogger.EVENT_FLUSH_WRITE, "OK",
                    Map.of("sessionId", command.sessionId() == null ? "" : command.sessionId()));
            }
        } catch (RuntimeException e) {
            if (command != null) {
                auditLogger.record(command.agentId(), command.userId(),
                    AiMemoryAuditLogger.EVENT_FLUSH_WRITE, "FAIL",
                    Map.of("reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
            throw e;
        }
    }

    @Override
    public String retrieve(MemoryQuery query) {
        return delegate.retrieve(query);
    }

    @Override
    public void delete(long userId) {
        try {
            delegate.delete(userId);
            auditLogger.record(null, userId, AiMemoryAuditLogger.EVENT_DELETE, "OK", null);
        } catch (RuntimeException e) {
            auditLogger.record(null, userId, AiMemoryAuditLogger.EVENT_DELETE, "FAIL",
                Map.of("reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
            throw e;
        }
    }

    @Override
    public MemoryBackendStats stats(long userId) {
        return delegate.stats(userId);
    }
}
