package org.dromara.ai.service.impl;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.mapper.AiAuditRetentionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiAuditRetentionServiceImpl} 单元测试：保留天数语义、超期行数统计与逐表物理清理。
 */
@DisplayName("审计数据保留策略")
class AiAuditRetentionServiceImplTest {

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final AiAuditRetentionMapper retentionMapper = mock(AiAuditRetentionMapper.class);

    private final AiAuditRetentionServiceImpl service =
        new AiAuditRetentionServiceImpl(runtimeConfigStore, retentionMapper);

    @Test
    @DisplayName("保留 0 天＝不清理：不统计超期、执行清理被拒")
    void shouldSkipWhenUnlimited() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(0));

        AiAuditRetentionVo vo = service.getRetention();
        assertEquals(0, vo.getRetentionDays());
        assertNull(vo.getCutoff());
        assertTrue(vo.getPending().isEmpty());
        assertNull(vo.getPurged());

        assertThrows(ServiceException.class, () -> service.purge());
        verifyNoInteractions(retentionMapper);
    }

    @Test
    @DisplayName("保留 N 天：按截止时间统计各表超期行数（6 表）")
    void shouldCountPendingRows() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.countTrace(any())).thenReturn(12L);
        when(retentionMapper.countRouteRun(any())).thenReturn(4L);

        AiAuditRetentionVo vo = service.getRetention();
        assertEquals(30, vo.getRetentionDays());
        assertNotNull(vo.getCutoff());
        assertEquals(6, vo.getPending().size());
        assertEquals(12L, vo.getPending().get("ai_trace"));
        assertEquals(4L, vo.getPending().get("ai_route_run"));
        assertEquals(0L, vo.getPending().get("ai_tool_call_log"));
        assertNull(vo.getPurged());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retentionMapper).countTrace(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now()));
        assertTrue(cutoff.getValue().isAfter(LocalDateTime.now().minusDays(31)));
    }

    @Test
    @DisplayName("保存保留天数写入运行期配置")
    void shouldSaveRetention() {
        AiAuditRetentionBo bo = bo(7);
        service.saveRetention(bo);
        verify(runtimeConfigStore).updateAuditRetention(bo);
    }

    @Test
    @DisplayName("清理：6 张表各按截止时间物理删除并回带逐表行数")
    void shouldPurgeByRetentionDays() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(7));
        when(retentionMapper.purgeTrace(any())).thenReturn(3);
        when(retentionMapper.purgeRouteRun(any())).thenReturn(5);

        AiAuditRetentionVo vo = service.purge();
        Map<String, Long> purged = vo.getPurged();
        assertNotNull(purged);
        assertEquals(6, purged.size());
        assertEquals(3L, purged.get("ai_trace"));
        assertEquals(5L, purged.get("ai_route_run"));
        assertEquals(0L, purged.get("ai_tool_call_log"));

        verify(retentionMapper).purgeTrace(any(LocalDateTime.class));
        verify(retentionMapper).purgeToolCallLog(any(LocalDateTime.class));
        verify(retentionMapper).purgePermissionDecisionLog(any(LocalDateTime.class));
        verify(retentionMapper).purgeAgentTaskRun(any(LocalDateTime.class));
        verify(retentionMapper).purgeRouteRun(any(LocalDateTime.class));
        verify(retentionMapper).purgeRouteExpertCall(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("清理对 6 张表使用同一截止时间（now − 保留天数）")
    void shouldUseSameCutoffForAllTables() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(7));

        service.purge();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retentionMapper).purgeTrace(cutoff.capture());
        verify(retentionMapper).purgeRouteExpertCall(cutoff.capture());
        List<LocalDateTime> values = cutoff.getAllValues();
        assertEquals(2, values.size());
        assertEquals(values.get(0), values.get(1));
        assertTrue(values.get(0).isBefore(LocalDateTime.now()));
        assertTrue(values.get(0).isAfter(LocalDateTime.now().minusDays(8)));
    }

    /**
     * 构造保留策略对象
     *
     * @param days 保留天数
     * @return 保留策略
     */
    private static AiAuditRetentionBo bo(int days) {
        AiAuditRetentionBo bo = new AiAuditRetentionBo();
        bo.setRetentionDays(days);
        return bo;
    }

}
