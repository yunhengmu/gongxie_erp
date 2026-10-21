package org.dromara.ai.core.run;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiAgentTaskRun;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.mapper.AiAgentTaskRunMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiStaleRunReaper} 单元测试：三类在跑状态的回收、关闭开关与单类失败降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("中断任务回收器")
class AiStaleRunReaperTest {

    @Mock
    private AiDocumentMapper documentMapper;

    @Mock
    private AiRouteRunMapper routeRunMapper;

    @Mock
    private AiAgentTaskRunMapper taskRunMapper;

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private AiStaleRunReaper reaper(boolean enabled) {
        AiProperties properties = new AiProperties();
        properties.getReaper().setEnabled(enabled);
        return new AiStaleRunReaper(documentMapper, routeRunMapper, taskRunMapper, properties);
    }

    @Test
    @DisplayName("三类在跑记录均落为终态，并写入可读的中断原因")
    void shouldReapAllKinds() {
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(routeRunMapper.update(any(AiRouteRun.class), any())).thenReturn(2);
        when(taskRunMapper.update(any(AiAgentTaskRun.class), any())).thenReturn(3);

        int total = reaper(true).reapNow();

        // 文档解析 + 文档向量化各 1 条，编排 2 条，任务执行 3 条
        assertEquals(7, total);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiDocument>> documentCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper, times(2)).update(any(), documentCaptor.capture());
        List<String> sets = documentCaptor.getAllValues().stream()
            .map(item -> String.valueOf(item.getSqlSet()))
            .toList();
        assertTrue(sets.stream().anyMatch(item -> item.contains("parse_status")));
        assertTrue(sets.stream().anyMatch(item -> item.contains("vector_status")));

        ArgumentCaptor<AiRouteRun> routeCaptor = ArgumentCaptor.forClass(AiRouteRun.class);
        verify(routeRunMapper).update(routeCaptor.capture(), any());
        assertEquals(AiRouteRun.STATUS_ABORTED, routeCaptor.getValue().getStatus());
        assertNotNull(routeCaptor.getValue().getDegradeReason());

        ArgumentCaptor<AiAgentTaskRun> taskCaptor = ArgumentCaptor.forClass(AiAgentTaskRun.class);
        verify(taskRunMapper).update(taskCaptor.capture(), any());
        assertEquals(AiAgentTaskRun.STATUS_FAILED, taskCaptor.getValue().getStatus());
        assertNotNull(taskCaptor.getValue().getEndTime());
        assertNotNull(taskCaptor.getValue().getErrorMsg());
    }

    @Test
    @DisplayName("开关关闭时不产生任何查询与更新")
    void shouldSkipWhenDisabled() {
        assertEquals(0, reaper(false).reapNow());

        verifyNoInteractions(documentMapper, routeRunMapper, taskRunMapper);
    }

    @Test
    @DisplayName("单类回收失败不阻断其余类别")
    void shouldContinueWhenOneKindFails() {
        when(documentMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));
        when(routeRunMapper.update(any(AiRouteRun.class), any())).thenReturn(1);
        when(taskRunMapper.update(any(AiAgentTaskRun.class), any())).thenReturn(1);

        assertEquals(2, reaper(true).reapNow());

        verify(routeRunMapper).update(any(AiRouteRun.class), any());
        verify(taskRunMapper).update(any(AiAgentTaskRun.class), any());
    }

}
