package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeSearchTool} 单元测试：绑定集合、入参校验、生效范围过滤、检索渲染与异常分支。
 */
@DisplayName("知识库检索工具")
class KnowledgeSearchToolTest {

    private final IAiVectorService vectorService = mock(IAiVectorService.class);
    private final IAiKnowledgeBaseService knowledgeBaseService = mock(IAiKnowledgeBaseService.class);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    private static AiRetrievalHitVo hit(Long docId, String docName, double score) {
        AiRetrievalHitVo vo = new AiRetrievalHitVo();
        vo.setDocId(docId);
        vo.setDocName(docName);
        vo.setChunkIndex(0);
        vo.setScore(score);
        vo.setContent("片段内容");
        return vo;
    }

    @Test
    @DisplayName("未绑定知识库时直接返回说明")
    void shouldReturnWhenNoKbBound() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(vectorService, List.of(), null, knowledgeBaseService);
        ToolResultBlock result = tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of(), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未绑定知识库"));
        verify(vectorService, never()).retrieve(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("检索关键词为空时校验失败")
    void shouldRejectWhenQueryBlank() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), null, knowledgeBaseService);
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "  "), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("检索关键词不能为空"));
    }

    @Test
    @DisplayName("生效范围内无可见（或已停用）知识库时给出说明")
    void shouldReturnWhenNoVisibleKb() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), null, knowledgeBaseService);
        when(knowledgeBaseService.filterRetrievableKbIds(any(), any())).thenReturn(List.of());
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "制度"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("均不在你的生效范围内"));
    }

    @Test
    @DisplayName("检索命中时按相似度渲染结果")
    void shouldRenderHits() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), null, knowledgeBaseService);
        when(knowledgeBaseService.filterRetrievableKbIds(any(), any())).thenReturn(List.of(1L));
        when(vectorService.retrieve(anyLong(), anyString(), anyInt(), any()))
            .thenReturn(List.of(hit(11L, "员工手册", 0.5), hit(12L, "报销制度", 0.9)));
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "制度"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("共 2 条"));
        assertTrue(text(result).contains("报销制度"));
        assertTrue(text(result).contains("员工手册"));
    }

    @Test
    @DisplayName("检索返回空时给出未检索到文案")
    void shouldHandleEmptyHits() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), null, knowledgeBaseService);
        when(knowledgeBaseService.filterRetrievableKbIds(any(), any())).thenReturn(List.of(1L));
        when(vectorService.retrieve(anyLong(), anyString(), anyInt(), any())).thenReturn(null);
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "制度"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("未检索到相关内容"));
    }

    @Test
    @DisplayName("平台出口模式跳过用户过滤并直接检索")
    void shouldSkipFilterInPlatformScope() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), 3, knowledgeBaseService, true);
        when(vectorService.retrieve(anyLong(), anyString(), anyInt(), any()))
            .thenReturn(List.of(hit(11L, "公开资料", 0.8)));
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "公开"), "100")).block();
        assertFalse(isError(result));
        assertTrue(text(result).contains("公开资料"));
        verify(knowledgeBaseService, never()).filterRetrievableKbIds(any(), any());
    }

    @Test
    @DisplayName("业务异常时回喂异常信息")
    void shouldReturnBusinessFailure() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), null, knowledgeBaseService);
        when(knowledgeBaseService.filterRetrievableKbIds(any(), any())).thenReturn(List.of(1L));
        when(vectorService.retrieve(anyLong(), anyString(), anyInt(), any()))
            .thenThrow(new ServiceException("向量库不可用"));
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "制度"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("向量库不可用"));
    }

    @Test
    @DisplayName("未知异常时包装为检索失败")
    void shouldReturnUnexpectedFailure() {
        KnowledgeSearchTool tool =
            new KnowledgeSearchTool(vectorService, List.of(1L), null, knowledgeBaseService);
        when(knowledgeBaseService.filterRetrievableKbIds(any(), any())).thenReturn(List.of(1L));
        when(vectorService.retrieve(anyLong(), anyString(), anyInt(), any()))
            .thenThrow(new IllegalStateException("boom"));
        ToolResultBlock result =
            tool.callAsync(param(KnowledgeSearchTool.TOOL_NAME, Map.of("query", "制度"), "100")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("知识库检索失败：boom"));
    }
}