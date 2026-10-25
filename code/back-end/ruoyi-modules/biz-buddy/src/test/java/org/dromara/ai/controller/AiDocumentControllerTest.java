package org.dromara.ai.controller;

import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.ai.service.IAiDocumentService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiDocumentController} 单元测试：文档 CRUD、解析、向量化与切片查询。
 */
@DisplayName("AI 知识库文档 Controller")
class AiDocumentControllerTest {

    private final IAiDocumentService documentService = mock(IAiDocumentService.class);

    private final IAiVectorService vectorService = mock(IAiVectorService.class);

    private final AiDocumentController controller = new AiDocumentController(documentService, vectorService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("分页查询文档列表")
    void shouldList() {
        AiDocumentBo bo = new AiDocumentBo();
        PageQuery query = new PageQuery();
        PageResult<AiDocumentVo> page = PageResult.build();
        when(documentService.queryPageList(bo, query)).thenReturn(page);
        assertSame(page, controller.list(bo, query).getData());
    }

    @Test
    @DisplayName("获取文档详情")
    void shouldGetInfo() {
        AiDocumentVo vo = new AiDocumentVo();
        when(documentService.queryById(2L)).thenReturn(vo);
        assertSame(vo, controller.getInfo(2L).getData());
    }

    @Test
    @DisplayName("查询文档纯文本内容")
    void shouldContent() {
        when(documentService.queryContent(2L)).thenReturn("正文");
        R<String> r = controller.content(2L);
        assertEquals("正文", r.getData());
    }

    @Test
    @DisplayName("分页查询文档切片")
    void shouldChunks() {
        PageQuery query = new PageQuery();
        PageResult<AiChunkVo> page = PageResult.build();
        when(documentService.queryChunkPage(2L, query)).thenReturn(page);
        assertSame(page, controller.chunks(2L, query).getData());
    }

    @Test
    @DisplayName("登记文档成功")
    void shouldAdd() {
        AiDocumentBo bo = new AiDocumentBo();
        when(documentService.insertByBo(bo)).thenReturn(true);
        assertTrue(R.isSuccess(controller.add(bo)));
    }

    @Test
    @DisplayName("解析文档返回文档详情")
    void shouldParse() {
        AiDocumentVo vo = new AiDocumentVo();
        when(documentService.parse(2L)).thenReturn(vo);
        assertSame(vo, controller.parse(2L).getData());
    }

    @Test
    @DisplayName("向量化文档转发到向量服务")
    void shouldVectorize() {
        AiDocumentVo vo = new AiDocumentVo();
        when(vectorService.vectorizeDocument(2L)).thenReturn(vo);
        assertSame(vo, controller.vectorize(2L).getData());
    }

    @Test
    @DisplayName("批量删除文档失败返回失败码")
    void shouldRemoveFail() {
        when(documentService.deleteWithValidByIds(List.of(1L), true)).thenReturn(false);
        assertFalse(R.isSuccess(controller.remove(new Long[]{1L})));
    }

}