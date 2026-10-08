package org.dromara.ai.job;

import com.aizuda.snailjob.model.dto.ExecuteResult;
import org.dromara.ai.service.IAiAgentTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AiAgentTaskDispatchJobExecutor} 单元测试：派发执行器把扫描结果包装成执行结果。
 */
@DisplayName("定时 Agent 任务派发执行器")
class AiAgentTaskDispatchJobExecutorTest {

    private final IAiAgentTaskService aiAgentTaskService = mock(IAiAgentTaskService.class);
    private final AiAgentTaskDispatchJobExecutor executor = new AiAgentTaskDispatchJobExecutor(aiAgentTaskService);

    @Test
    @DisplayName("派发成功：返回触发数量")
    void shouldReportTriggeredCount() {
        when(aiAgentTaskService.dispatchDueTasks()).thenReturn(3);

        ExecuteResult result = executor.jobExecute(null);

        assertNotNull(result);
        assertEquals("本次触发 3 个任务", result.getResult());
        verify(aiAgentTaskService, times(1)).dispatchDueTasks();
    }

    @Test
    @DisplayName("零触发也正常返回")
    void shouldHandleZeroTriggered() {
        when(aiAgentTaskService.dispatchDueTasks()).thenReturn(0);

        ExecuteResult result = executor.jobExecute(null);
        assertEquals("本次触发 0 个任务", result.getResult());
        verify(aiAgentTaskService).dispatchDueTasks();
    }

}