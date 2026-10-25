package org.dromara.ai.core.memory.spi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.common.core.utils.StringUtils;

/**
 * 文件式记忆后端（docs/24 §5.10）。
 *
 * <p>包装现有平台记忆服务：记忆真值仍是工作区内的 {@code MEMORY.md} 与每日流水，
 * 由框架的抽取与合并钩子负责写入；本后端只暴露统一的读 / 删 / 统计契约，
 * 供上层装饰器（净化、审计、计量、降级）串接。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
public class FileMemoryBackend implements AiMemoryBackend {

    private final IAiMemoryService memoryService;

    private final IAiAgentService agentService;

    @Override
    public void record(MemoryRecordCommand command) {
        // 文件式记忆的流水由框架抽取钩子落盘，平台侧不重复写入；
        // 审计与计量由外层装饰器负责，避免同一次写入被记两遍
    }

    @Override
    public String retrieve(MemoryQuery query) {
        if (query == null || query.userId() == null) {
            return "";
        }
        Long agentId = entryAgentId();
        if (agentId == null) {
            return "";
        }
        String memory = memoryService.readContent(agentId, query.userId(), "MEMORY.md").getContent();
        if (StringUtils.isBlank(memory)) {
            return "";
        }
        int maxChars = query.maxChars() > 0 ? query.maxChars() : memory.length();
        return memory.length() <= maxChars ? memory : memory.substring(0, maxChars);
    }

    @Override
    public void delete(long userId) {
        Long agentId = entryAgentId();
        if (agentId == null) {
            return;
        }
        memoryService.deleteAll(agentId, userId);
    }

    @Override
    public MemoryBackendStats stats(long userId) {
        Long agentId = entryAgentId();
        if (agentId == null) {
            return MemoryBackendStats.empty(userId);
        }
        return memoryService.stats(agentId, userId);
    }

    /**
     * 解析入口智能体主键：长期记忆按入口工作区的用户命名空间隔离，专家共享同一份。
     *
     * @return 入口智能体主键；未配置时返回 null
     */
    private Long entryAgentId() {
        AiAgent entry = agentService.getEntryAgent();
        if (entry == null) {
            log.warn("未配置入口智能体，文件式记忆后端无法定位工作区");
            return null;
        }
        return entry.getId();
    }
}
