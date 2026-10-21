package org.dromara.ai.service.impl;

import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.domain.vo.AiOnboardingReportVo;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiHealthCheckService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link AiOnboardingServiceImpl} 单元测试：清单完成判据与健康自检联动。
 */
@DisplayName("首登检查清单")
class AiOnboardingServiceImplTest {

    private final IAiHealthCheckService healthCheckService = mock(IAiHealthCheckService.class);
    private final AiDocumentMapper documentMapper = mock(AiDocumentMapper.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final AiOnboardingServiceImpl service = new AiOnboardingServiceImpl(
        healthCheckService, documentMapper, messageMapper);

    private MockedStatic<LoginHelper> loginHelper;

    @BeforeAll
    static void initMybatis() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        loginHelper = mockStatic(LoginHelper.class);
        loginHelper.when(LoginHelper::getUserId).thenReturn(9L);
    }

    @AfterEach
    void tearDown() {
        loginHelper.close();
    }

    @Test
    @DisplayName("模型、文档与首轮对话均就绪时清单全部完成")
    void shouldReportAllDone() {
        when(healthCheckService.check()).thenReturn(report(
            item("model.chat.key", AiHealthCheckVo.STATUS_PASS),
            item("model.embedding", AiHealthCheckVo.STATUS_PASS)));
        when(documentMapper.selectCount(any())).thenReturn(1L);
        when(messageMapper.selectCount(any())).thenReturn(1L);

        AiOnboardingReportVo report = service.checklist();

        assertEquals(4, report.getRequiredCount());
        assertEquals(4, report.getDoneCount());
        assertTrue(report.isAllDone());
        assertTrue(report.getItems().stream().allMatch(item -> item.isDone()));
    }

    @Test
    @DisplayName("密钥缺失、无文档且未对话时清单均未完成")
    void shouldReportNothingDone() {
        when(healthCheckService.check()).thenReturn(report(
            item("model.chat.key", AiHealthCheckVo.STATUS_FAIL),
            item("model.embedding", AiHealthCheckVo.STATUS_FAIL)));
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.selectCount(any())).thenReturn(0L);

        AiOnboardingReportVo report = service.checklist();

        assertEquals(0, report.getDoneCount());
        assertFalse(report.isAllDone());
        assertTrue(report.getItems().stream().noneMatch(item -> item.isDone()));
    }

    @Test
    @DisplayName("健康自检抛异常时按未就绪处理，不影响清单返回")
    void shouldFallbackWhenHealthCheckFails() {
        when(healthCheckService.check()).thenThrow(new IllegalStateException("boom"));
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.selectCount(any())).thenReturn(0L);

        AiOnboardingReportVo report = service.checklist();

        assertEquals(4, report.getRequiredCount());
        assertEquals(0, report.getDoneCount());
        assertFalse(report.isAllDone());
    }

    private static AiHealthReportVo report(AiHealthCheckVo... items) {
        AiHealthReportVo report = new AiHealthReportVo();
        report.setItems(List.of(items));
        return report;
    }

    private static AiHealthCheckVo item(String key, String status) {
        AiHealthCheckVo vo = new AiHealthCheckVo();
        vo.setKey(key);
        vo.setStatus(status);
        return vo;
    }

}
