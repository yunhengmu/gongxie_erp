package org.dromara.ai.controller;

import org.dromara.ai.config.AiProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRetrievalController} 单元测试：检索测试的可见性校验与召回转发（含重排参数）。
 */
@DisplayName("AI 知识库检索测试 Controller")
class AiRetrievalControllerTest {

    private final IAiVectorService vectorService = mock(IAiVectorService.class);

    private final IAiKnowledgeBaseService kbService = mock(IAiKnowledgeBaseService.class);

    private final AiProperties aiProperties = new AiProperties();

    private final AiRetrievalController controller = new AiRetrievalController(vectorService, kbService, aiProperties);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("检索测试先校验可见性再召回（未传 rerank 时跟随配置，默认开）")
    void shouldTest() {
        List<AiRetrievalHitVo> hits = List.of(new AiRetrievalHitVo());
        when(vectorService.retrieve(eq(3L), eq("查询"), eq(5), eq(new BigDecimal("0.5")), eq(true), isNull())).thenReturn(hits);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(hits, controller.test(3L, "查询", 5, new BigDecimal("0.5"), null, null, null).getData());
        }
        verify(kbService).validateVisible(3L, 1L, "检索");
    }

    @Test
    @DisplayName("检索测试未指定条数与阈值时兜底")
    void shouldTestWithNullOptions() {
        List<AiRetrievalHitVo> hits = List.of();
        when(vectorService.retrieve(eq(3L), eq("查询"), isNull(), isNull(), eq(true), isNull())).thenReturn(hits);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(hits, controller.test(3L, "查询", null, null, null, null, null).getData());
        }
    }

    @Test
    @DisplayName("显式传 rerank=true 时透传开启重排")
    void shouldPassThroughRerank() {
        List<AiRetrievalHitVo> hits = List.of(new AiRetrievalHitVo());
        when(vectorService.retrieve(eq(3L), eq("查询"), eq(5), isNull(), eq(true), isNull())).thenReturn(hits);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(hits, controller.test(3L, "查询", 5, null, true, null, null).getData());
        }
        verify(vectorService).retrieve(3L, "查询", 5, null, true, null);
    }

    @Test
    @DisplayName("显式传 rerankMode 时透传融合模式")
    void shouldPassThroughRerankMode() {
        List<AiRetrievalHitVo> hits = List.of(new AiRetrievalHitVo());
        when(vectorService.retrieve(eq(3L), eq("查询"), eq(5), isNull(), eq(true), eq("rank_rrf"))).thenReturn(hits);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertSame(hits, controller.test(3L, "查询", 5, null, true, null, "rank_rrf").getData());
        }
        verify(vectorService).retrieve(3L, "查询", 5, null, true, "rank_rrf");
    }

    @Test
    @DisplayName("显式传 assemble=true 时对命中应用上下文装配（相邻切片合并）")
    void shouldAssembleWhenRequested() {
        AiRetrievalHitVo first = new AiRetrievalHitVo();
        first.setDocId(11L);
        first.setDocName("员工手册");
        first.setChunkIndex(2);
        first.setScore(0.9);
        first.setContent("考勤规定正文。");
        AiRetrievalHitVo second = new AiRetrievalHitVo();
        second.setDocId(11L);
        second.setDocName("员工手册");
        second.setChunkIndex(3);
        second.setScore(0.8);
        second.setContent("请假规定正文。");
        when(vectorService.retrieve(eq(3L), eq("查询"), eq(5), isNull(), eq(true), isNull()))
            .thenReturn(List.of(first, second));
        List<AiRetrievalHitVo> assembled;
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assembled = controller.test(3L, "查询", 5, null, null, true, null).getData();
        }
        assertEquals(1, assembled.size());
        assertTrue(assembled.get(0).getContent().contains("考勤规定正文"));
        assertTrue(assembled.get(0).getContent().contains("请假规定正文"));
    }

}