package org.dromara.ai.core.memory;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiMemoryMaintenanceTask} 单元测试：覆盖执行顺序、合并失败跳过归档与用户覆盖范围。
 */
@DisplayName("长期记忆维护任务")
class AiMemoryMaintenanceTaskTest {

    private final AiProperties aiProperties = new AiProperties();

    private final AiRuntimeConfigStore configStore = mock(AiRuntimeConfigStore.class);

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final IAiMemoryService memoryService = mock(IAiMemoryService.class);

    private final AiSessionMapper sessionMapper = mock(AiSessionMapper.class);

    private final AiMemoryMaintenanceTask task =
        new AiMemoryMaintenanceTask(aiProperties, configStore, agentService, memoryService, sessionMapper);

    @BeforeAll
    static void initStaticContext() {
        // RedisUtils 静态字段需要容器中的 RedissonClient，否则静态 mock 会失败并污染 matcher 栈
        AiTestSupport.initialize();
    }

    @BeforeEach
    void prepare() {
        AiMemoryConfigBo policy = new AiMemoryConfigBo();
        policy.setEnabled(true);
        when(configStore.memory()).thenReturn(policy);
        AiAgent entry = mock(AiAgent.class);
        when(entry.getId()).thenReturn(1L);
        when(agentService.getEntryAgent()).thenReturn(entry);
        when(sessionMapper.selectDistinctUserIds()).thenReturn(List.of(2L));
        when(memoryService.listMemoryUserIds(1L)).thenReturn(List.of(3L));
    }

    @Test
    @DisplayName("按合并、归档、清理顺序执行，并覆盖会话用户与记忆用户")
    void shouldRunConsolidateArchiveCleanupInOrder() {
        when(memoryService.hasPendingLedger(anyLong(), anyLong())).thenReturn(true);
        try (MockedStatic<RedisUtils> redis = acquireLock()) {
            task.runNow();
        }

        InOrder order = inOrder(memoryService);
        order.verify(memoryService).hasPendingLedger(1L, 2L);
        order.verify(memoryService).consolidate(1L, 2L);
        order.verify(memoryService).archiveDaily(1L, 2L);
        order.verify(memoryService).cleanup(1L, 2L);
        order.verify(memoryService).hasPendingLedger(1L, 3L);
        order.verify(memoryService).archiveDaily(1L, 3L);
        order.verify(memoryService).cleanup(1L, 3L);
    }

    @Test
    @DisplayName("无 pending 流水时跳过合并但仍归档清理")
    void shouldSkipConsolidateWhenNoPendingLedger() {
        when(memoryService.hasPendingLedger(1L, 2L)).thenReturn(false);
        when(memoryService.hasPendingLedger(1L, 3L)).thenReturn(false);
        try (MockedStatic<RedisUtils> redis = acquireLock()) {
            task.runNow();
        }

        verify(memoryService, never()).consolidate(anyLong(), anyLong());
        verify(memoryService).archiveDaily(1L, 2L);
        verify(memoryService).cleanup(1L, 2L);
        verify(memoryService).archiveDaily(1L, 3L);
        verify(memoryService).cleanup(1L, 3L);
    }

    @Test
    @DisplayName("合并失败时跳过该用户归档与清理")
    void shouldSkipArchiveWhenConsolidateFails() {
        when(memoryService.hasPendingLedger(anyLong(), anyLong())).thenReturn(true);
        doThrow(new ServiceException("模型不可用")).when(memoryService).consolidate(1L, 2L);
        try (MockedStatic<RedisUtils> redis = acquireLock()) {
            task.runNow();
        }

        verify(memoryService, never()).archiveDaily(1L, 2L);
        verify(memoryService, never()).cleanup(1L, 2L);
        // 失败只影响当前用户，后续用户继续维护
        verify(memoryService).archiveDaily(1L, 3L);
    }

    @Test
    @DisplayName("未抢到执行锁时直接跳过")
    void shouldSkipWhenLockNotAcquired() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
            assertEquals(0, task.runNow());
        }
        verifyNoInteractions(memoryService);
    }

    private MockedStatic<RedisUtils> acquireLock() {
        MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class);
        redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);
        return redis;
    }
}
