package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiHealthProbeBo;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.service.IAiHealthCheckService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.R;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiHealthController} 单元测试：自检与探测的转发。
 */
@DisplayName("平台健康自检 Controller")
class AiHealthControllerTest {

    private final IAiHealthCheckService healthCheckService = mock(IAiHealthCheckService.class);

    private final AiHealthController controller = new AiHealthController(healthCheckService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("自检转发到服务并返回报告")
    void shouldCheck() {
        AiHealthReportVo report = new AiHealthReportVo();
        report.setOverall(AiHealthCheckVo.STATUS_PASS);
        when(healthCheckService.check()).thenReturn(report);
        R<AiHealthReportVo> r = controller.check();
        assertTrue(R.isSuccess(r));
        assertSame(report, r.getData());
    }

    @Test
    @DisplayName("探测转发到服务并返回该项结果")
    void shouldProbe() {
        AiHealthCheckVo item = new AiHealthCheckVo();
        item.setKey("probe:milvus");
        item.setStatus(AiHealthCheckVo.STATUS_PASS);
        AiHealthProbeBo bo = new AiHealthProbeBo();
        bo.setTarget(AiHealthCheckVo.TARGET_MILVUS);
        when(healthCheckService.probe(bo)).thenReturn(item);
        R<AiHealthCheckVo> r = controller.probe(bo);
        assertTrue(R.isSuccess(r));
        assertSame(item, r.getData());
        verify(healthCheckService).probe(bo);
    }

}
