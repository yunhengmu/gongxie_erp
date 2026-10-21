package org.dromara.ai.service.impl;

import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.vo.AiChunkContextVo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.mapper.AiChunkMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 知识库切片定位（参考来源点击）单元测试。
 *
 * <p>重点覆盖三类边界：命中与邻居切分、序号越界（文档重新解析后来源序号可能失效）、
 * 越权访问（来源只带 docId，必须按知识库可见范围拦截）。</p>
 *
 * @author BizBuddy
 */
@ExtendWith(MockitoExtension.class)
class AiDocumentServiceImplTest {

    private static final Long DOC_ID = 100L;
    private static final Long KB_ID = 10L;
    private static final Long USER_ID = 7L;

    @Mock
    private AiDocumentMapper documentMapper;

    @Mock
    private AiChunkMapper chunkMapper;

    @Mock
    private IAiKnowledgeBaseService knowledgeBaseService;

    @Mock
    private IAiVectorService aiVectorService;

    @Mock
    private OssService ossService;

    private AiDocumentServiceImpl service;

    @BeforeAll
    static void initMybatisPlus() {
        // 纯单测不起容器：先注册 TableInfo，否则 LambdaQueryWrapper 取不到列缓存
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        service = new AiDocumentServiceImpl(documentMapper, chunkMapper, knowledgeBaseService, aiVectorService, ossService);
    }

    @Test
    @DisplayName("命中切片：返回目标与前后邻居")
    void shouldReturnTargetWithNeighbors() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);
            when(documentMapper.selectById(DOC_ID)).thenReturn(document());
            when(knowledgeBaseService.filterVisibleKbIds(anyCollection(), eq(USER_ID))).thenReturn(List.of(KB_ID));
            when(chunkMapper.selectVoList(any())).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));

            AiChunkContextVo context = service.queryChunkContext(DOC_ID, 2, 1);

            assertEquals(2, context.getTarget().getChunkIndex());
            assertEquals(1, context.getBefore().size());
            assertEquals(1, context.getAfter().size());
        }
    }

    @Test
    @DisplayName("序号越界：目标为空但不报错（文档重新解析后来源可能失效）")
    void shouldReturnNullTargetWhenIndexMissing() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);
            when(documentMapper.selectById(DOC_ID)).thenReturn(document());
            when(knowledgeBaseService.filterVisibleKbIds(anyCollection(), eq(USER_ID))).thenReturn(List.of(KB_ID));
            when(chunkMapper.selectVoList(any())).thenReturn(List.of(chunk(1)));

            AiChunkContextVo context = service.queryChunkContext(DOC_ID, 9, 1);

            assertNull(context.getTarget());
            assertEquals(1, context.getBefore().size());
            assertEquals(0, context.getAfter().size());
        }
    }

    @Test
    @DisplayName("越权：知识库不在当前用户可见范围时拒绝")
    void shouldRejectInvisibleKnowledgeBase() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);
            when(documentMapper.selectById(DOC_ID)).thenReturn(document());
            when(knowledgeBaseService.filterVisibleKbIds(anyCollection(), eq(USER_ID))).thenReturn(List.of());

            ServiceException ex = assertThrows(ServiceException.class,
                () -> service.queryChunkContext(DOC_ID, 1, 1));
            assertEquals("无权访问该知识库文档", ex.getMessage());
        }
    }

    @Test
    @DisplayName("文档不存在：直接拒绝，不查切片")
    void shouldRejectMissingDocument() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.queryChunkContext(DOC_ID, 1, 1));
        assertEquals("文档不存在或已被删除", ex.getMessage());
    }

    @Test
    @DisplayName("非法入参：序号为空或负数时拒绝")
    void shouldRejectInvalidIndex() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.queryChunkContext(DOC_ID, -1, 1));
        assertEquals("文档与切片序号不能为空", ex.getMessage());
    }

    private AiDocument document() {
        AiDocument document = new AiDocument();
        document.setId(DOC_ID);
        document.setKbId(KB_ID);
        return document;
    }

    private AiChunkVo chunk(int index) {
        AiChunkVo vo = new AiChunkVo();
        vo.setDocId(DOC_ID);
        vo.setKbId(KB_ID);
        vo.setChunkIndex(index);
        vo.setContent("第 " + index + " 段正文");
        return vo;
    }

}
