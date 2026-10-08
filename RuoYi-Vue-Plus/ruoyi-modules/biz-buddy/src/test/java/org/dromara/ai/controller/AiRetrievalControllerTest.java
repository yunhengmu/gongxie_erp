package org.dromara.ai.controller;

import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRetrievalController} 单元测试：检索测试的可见性校验与召回转发。
 */
@DisplayName("AI 知识库检索测试 Controller")
class AiRetrievalControllerTest {

    private final IAiVectorService vectorService = mock(IAiVectorService.class);

    private final IAiKnowledgeBaseService kbService = mock(IAiKnowledgeBaseService.class);

    private final AiRetrievalController controller = new AiRetrievalController(vectorService, kbService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("检索测试先校验可见性再召回")
    void shouldTest() {
        List<AiRetrievalHitVo> hits = List.of(new AiRetrievalHitVo());
        when(vectorService.retrieve(eq(3L), eq("查询"), eq(5), eq(new BigDecimal("0.5")))).thenReturn(hits);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(hits, controller.test(3L, "查询", 5, new BigDecimal("0.5")).getData());
        }
        verify(kbService).validateVisible(3L, 1L, "检索");
    }

    @Test
    @DisplayName("检索测试未指定条数与阈值时兜底")
    void shouldTestWithNullOptions() {
        List<AiRetrievalHitVo> hits = List.of();
        when(vectorService.retrieve(eq(3L), eq("查询"), isNull(), isNull())).thenReturn(hits);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(hits, controller.test(3L, "查询", null, null).getData());
        }
    }

}