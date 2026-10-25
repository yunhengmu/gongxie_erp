package org.dromara.ai.job;

import org.dromara.ai.service.IAiAgentTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiAgentTaskDispatchScheduler} 单元测试：派发调用与异常隔离。
 */
@DisplayName("定时 Agent 任务调度器")
class AiAgentTaskDispatchSchedulerTest {

    private final IAiAgentTaskService aiAgentTaskService = mock(IAiAgentTaskService.class);
    private final AiAgentTaskDispatchScheduler scheduler = new AiAgentTaskDispatchScheduler(aiAgentTaskService);

    @Test
    @DisplayName("派发成功：返回触发数量")
    void shouldReportTriggeredCount() {
        when(aiAgentTaskService.dispatchDueTasks()).thenReturn(3);

        assertEquals(3, scheduler.tick());
        verify(aiAgentTaskService, times(1)).dispatchDueTasks();
    }

    @Test
    @DisplayName("零触发也正常返回")
    void shouldHandleZeroTriggered() {
        when(aiAgentTaskService.dispatchDueTasks()).thenReturn(0);

        assertEquals(0, scheduler.tick());
        verify(aiAgentTaskService).dispatchDueTasks();
    }

    @Test
    @DisplayName("派发异常只记录，不向调度线程抛出")
    void shouldIsolateDispatchFailure() {
        when(aiAgentTaskService.dispatchDueTasks()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(scheduler::dispatchQuietly);
        verify(aiAgentTaskService).dispatchDueTasks();
    }

}
