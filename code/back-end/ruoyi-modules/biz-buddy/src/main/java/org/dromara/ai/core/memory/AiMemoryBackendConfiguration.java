package org.dromara.ai.core.memory;

import org.dromara.ai.core.memory.spi.AiMemoryBackend;
import org.dromara.ai.core.memory.spi.AuditMemoryBackend;
import org.dromara.ai.core.memory.spi.FallbackMemoryBackend;
import org.dromara.ai.core.memory.spi.FileMemoryBackend;
import org.dromara.ai.core.memory.spi.MeteringMemoryBackend;
import org.dromara.ai.core.memory.spi.SanitizingMemoryBackend;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiUsageStatService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆后端装配（docs/24 §5.10）。
 *
 * <p>装饰器链顺序固定为「净化 → 审计 → 计量 → 降级 → 具体后端」。换成 mem0 / ReMe 适配实现时，
 * 只需替换最内层实现，治理逻辑保持不变。</p>
 *
 * @author BizBuddy
 */
@Configuration
public class AiMemoryBackendConfiguration {

    /**
     * 装配记忆后端：外层净化、中间审计与计量、内层降级与文件式实现。
     *
     * @param memoryService    记忆服务
     * @param agentService     智能体服务
     * @param sanitizer        净化器
     * @param auditLogger      审计日志
     * @param usageStatService 用量统计
     * @return 已装饰的记忆后端
     */
    @Bean
    public AiMemoryBackend aiMemoryBackend(IAiMemoryService memoryService,
                                           IAiAgentService agentService,
                                           AiMemorySanitizer sanitizer,
                                           AiMemoryAuditLogger auditLogger,
                                           IAiUsageStatService usageStatService) {
        AiMemoryBackend backend = new FileMemoryBackend(memoryService, agentService);
        backend = new FallbackMemoryBackend(backend);
        backend = new MeteringMemoryBackend(backend, usageStatService);
        backend = new AuditMemoryBackend(backend, auditLogger);
        return new SanitizingMemoryBackend(backend, sanitizer);
    }
}
