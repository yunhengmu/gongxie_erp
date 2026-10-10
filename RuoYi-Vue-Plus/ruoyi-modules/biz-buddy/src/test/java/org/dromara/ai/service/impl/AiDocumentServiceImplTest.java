package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiChunk;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.ai.mapper.AiChunkMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.ai.util.AiDocumentParser;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link AiDocumentServiceImpl} 单元测试：文档新增校验、解析流程（下载/切片/清向量）与删除、内容预览。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI 知识库文档 Service")
class AiDocumentServiceImplTest {

    @Mock
    private AiDocumentMapper baseMapper;

    @Mock
    private AiChunkMapper chunkMapper;

    @Mock
    private IAiKnowledgeBaseService knowledgeBaseService;

    @Mock
    private IAiVectorService aiVectorService;

    @Mock
    private OssService ossService;

    @InjectMocks
    private AiDocumentServiceImpl service;

    @BeforeAll
    static void initContext() {
        AiServiceTestContext.initialize();
    }

    private AiKnowledgeBase kb(Long id, Integer chunkSize, Integer overlap) {
        AiKnowledgeBase kb = new AiKnowledgeBase();
        kb.setId(id);
        kb.setCollectionName("col");
        kb.setDimension(3);
        kb.setChunkSize(chunkSize);
        kb.setChunkOverlap(overlap);
        return kb;
    }

    private AiDocument document(Long id, Long kbId, String suffix, Long ossId, String parseStatus) {
        AiDocument document = new AiDocument();
        document.setId(id);
        document.setKbId(kbId);
        document.setDocName("d");
        document.setFileSuffix(suffix);
        document.setOssId(ossId);
        document.setParseStatus(parseStatus);
        return document;
    }

    @Test
    @DisplayName("分页查询文档")
    void shouldQueryPageList() {
        Page<AiDocumentVo> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(new AiDocumentVo())));
        page.setTotal(1L);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiDocumentVo> result = service.queryPageList(new AiDocumentBo(), new PageQuery(1, 10));

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRows().size());
    }

    @Test
    @DisplayName("按主键查询文档")
    void shouldQueryById() {
        AiDocumentVo vo = new AiDocumentVo();
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        assertSame(vo, service.queryById(1L));
    }

    @Test
    @DisplayName("新增文档：不支持的格式抛异常")
    void shouldRejectUnsupportedSuffix() {
        AiDocumentBo bo = new AiDocumentBo();
        bo.setKbId(1L);
        AiDocument entity = document(null, 1L, "exe", 5L, null);
        when(knowledgeBaseService.getEnabledKnowledgeBase(1L)).thenReturn(kb(1L, 500, 0));
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiDocument.class)).thenReturn(entity);
            ps.when(() -> AiDocumentParser.supported("exe")).thenReturn(false);

            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("新增文档：文件未上传抛异常")
    void shouldRejectMissingOssId() {
        AiDocumentBo bo = new AiDocumentBo();
        bo.setKbId(1L);
        AiDocument entity = document(null, 1L, "txt", null, null);
        when(knowledgeBaseService.getEnabledKnowledgeBase(1L)).thenReturn(kb(1L, 500, 0));
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiDocument.class)).thenReturn(entity);
            ps.when(() -> AiDocumentParser.supported("txt")).thenReturn(true);

            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("新增文档：解析 URL、校验同名并插入")
    void shouldInsertDocument() {
        AiDocumentBo bo = new AiDocumentBo();
        bo.setKbId(1L);
        AiDocument entity = document(null, 1L, ".TXT", 5L, null);
        when(knowledgeBaseService.getEnabledKnowledgeBase(1L)).thenReturn(kb(1L, 500, 0));
        when(ossService.selectUrlByIds("5")).thenReturn("http://a,http://b");
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(AiDocument.class))).thenAnswer(inv -> {
            AiDocument arg = inv.getArgument(0);
            arg.setId(88L);
            return 1;
        });
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiDocument.class)).thenReturn(entity);
            ps.when(() -> AiDocumentParser.supported("txt")).thenReturn(true);

            assertTrue(service.insertByBo(bo));
        }
        assertEquals("http://a", entity.getFileUrl());
        assertEquals("0", entity.getParseStatus());
        assertEquals(88L, bo.getId());
    }

    @Test
    @DisplayName("新增文档：同名文档抛异常")
    void shouldRejectDuplicateDocName() {
        AiDocumentBo bo = new AiDocumentBo();
        bo.setKbId(1L);
        AiDocument entity = document(null, 1L, "txt", 5L, null);
        when(knowledgeBaseService.getEnabledKnowledgeBase(1L)).thenReturn(kb(1L, 500, 0));
        when(ossService.selectUrlByIds("5")).thenReturn("http://a");
        when(baseMapper.selectCount(any())).thenReturn(1L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiDocument.class)).thenReturn(entity);
            ps.when(() -> AiDocumentParser.supported("txt")).thenReturn(true);

            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("解析文档：不存在或正在解析均抛异常")
    void shouldRejectParsePreconditions() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.parse(1L));

        when(baseMapper.selectById(2L)).thenReturn(document(2L, 1L, "txt", 5L, "1"));
        assertThrows(ServiceException.class, () -> service.parse(2L));
    }

    @Test
    @DisplayName("解析文档：下载、切片、清理旧向量并更新状态")
    void shouldParseDocument() {
        AiDocument doc = document(1L, 2L, "txt", 5L, "0");
        when(baseMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb(2L, 10, 0));
        AiChunk old = new AiChunk();
        old.setDocId(1L);
        old.setVectorId("v1");
        when(chunkMapper.selectList(any())).thenReturn(List.of(old));
        when(ossService.selectInternalUrlByIds("5")).thenReturn("http://minio/a");
        when(baseMapper.selectVoById(1L)).thenReturn(new AiDocumentVo());
        try (MockedStatic<HttpUtil> hs = mockStatic(HttpUtil.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            hs.when(() -> HttpUtil.downloadBytes("http://minio/a")).thenReturn(new byte[]{1, 2, 3});
            ps.when(() -> AiDocumentParser.extract(any(byte[].class), eq("txt"))).thenReturn("hello world");

            AiDocumentVo result = service.parse(1L);

            assertNotNull(result);
            verify(chunkMapper).delete(any());
            verify(chunkMapper).insertBatch(any());
            verify(aiVectorService).removeVectors(eq(2L), eq(List.of("v1")));
        }
    }

    @Test
    @DisplayName("解析文档：未提取到文本抛异常")
    void shouldRejectEmptyParseText() {
        AiDocument doc = document(1L, 2L, "txt", 5L, "0");
        when(baseMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb(2L, 10, 0));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(ossService.selectInternalUrlByIds("5")).thenReturn("http://minio/a");
        try (MockedStatic<HttpUtil> hs = mockStatic(HttpUtil.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            hs.when(() -> HttpUtil.downloadBytes("http://minio/a")).thenReturn(new byte[]{1});
            ps.when(() -> AiDocumentParser.extract(any(byte[].class), eq("txt"))).thenReturn("  ");

            assertThrows(ServiceException.class, () -> service.parse(1L));
        }
        verify(baseMapper, atLeast(2)).updateById(any(AiDocument.class));
    }

    @Test
    @DisplayName("解析文档：下载失败包装为业务异常")
    void shouldWrapDownloadFailure() {
        AiDocument doc = document(1L, 2L, "txt", 5L, "0");
        when(baseMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb(2L, 10, 0));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(ossService.selectInternalUrlByIds("5")).thenReturn("http://minio/a");
        try (MockedStatic<HttpUtil> hs = mockStatic(HttpUtil.class)) {
            hs.when(() -> HttpUtil.downloadBytes("http://minio/a")).thenThrow(new RuntimeException("io"));

            assertThrows(ServiceException.class, () -> service.parse(1L));
        }
    }

    @Test
    @DisplayName("删除文档：校验数量不匹配抛异常")
    void shouldRejectDeleteWhenCountMismatch() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L), true));
    }

    @Test
    @DisplayName("删除文档：先清向量再删业务数据")
    void shouldDeleteDocument() {
        AiDocument doc = document(1L, 2L, "txt", 5L, "2");
        AiChunk chunk = new AiChunk();
        chunk.setDocId(1L);
        chunk.setVectorId("v1");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(baseMapper.selectByIds(any())).thenReturn(List.of(doc));
        when(knowledgeBaseService.queryEntity(2L)).thenReturn(kb(2L, 10, 0));
        when(baseMapper.deleteByIds(any())).thenReturn(1);

        assertTrue(service.deleteWithValidByIds(List.of(1L), false));

        verify(aiVectorService).removeVectors(eq(2L), eq(List.of("v1")));
        verify(chunkMapper).delete(any());
    }

    @Test
    @DisplayName("分页查询切片")
    void shouldQueryChunkPage() {
        Page<AiChunkVo> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(new AiChunkVo())));
        page.setTotal(1L);
        when(chunkMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiChunkVo> result = service.queryChunkPage(1L, new PageQuery(1, 10));

        assertEquals(1L, result.getTotal());
    }

    @Test
    @DisplayName("查询文档正文：不存在/未解析抛异常，正常返回，超长截断")
    void shouldQueryContent() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.queryContent(1L));

        AiDocument blank = document(2L, 2L, "txt", 5L, "0");
        blank.setContent("  ");
        when(baseMapper.selectById(2L)).thenReturn(blank);
        assertThrows(ServiceException.class, () -> service.queryContent(2L));

        AiDocument normal = document(3L, 2L, "txt", 5L, "2");
        normal.setContent("正文");
        when(baseMapper.selectById(3L)).thenReturn(normal);
        assertEquals("正文", service.queryContent(3L));

        AiDocument huge = document(4L, 2L, "txt", 5L, "2");
        huge.setContent("a".repeat(200_001));
        when(baseMapper.selectById(4L)).thenReturn(huge);
        assertTrue(service.queryContent(4L).contains("内容过长"));
    }

    @Test
    @DisplayName("切片：换行回缩后不得丢字，且相邻切片保持重叠")
    void shouldKeepCoverageAndOverlapWhenBreakingAtNewline() {
        // 首行 60 字符，使换行落在切片窗口后半段，触发 end 回缩到换行处；
        // 其后接 140 个内容唯一的字符，避免 indexOf 定位产生歧义
        String text = "a".repeat(60) + "\n" + "abcdefghij".repeat(14);
        int size = 100;
        int overlap = 20;

        List<String> pieces = AiDocumentServiceImpl.split(text, size, overlap);

        assertTrue(pieces.size() > 1, "应切出多个切片");
        pieces.forEach(piece -> assertTrue(piece.length() <= size, "切片超长：" + piece.length()));
        int firstPos = text.indexOf(pieces.get(0));
        assertEquals(0, firstPos, "首片应自文首开始");
        // 逐片回原定位：后续片不得出现空隙，且须保持配置的重叠量
        int cursor = firstPos + pieces.get(0).length();
        int searchFrom = 0;
        for (int i = 1; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            int pos = text.indexOf(piece, searchFrom);
            assertTrue(pos >= 0, "切片内容在原文中缺失：" + piece);
            // 容差 1 字符：切片边界上的换行会被 trim 去掉
            assertTrue(pos <= cursor + 1, "出现丢字空隙：cursor=" + cursor + " pos=" + pos);
            assertTrue(cursor - pos >= overlap - 1, "相邻切片未保持重叠：cursor=" + cursor + " pos=" + pos);
            cursor = pos + piece.length();
            searchFrom = Math.max(0, cursor - overlap - 1);
        }
        assertTrue(text.trim().endsWith(pieces.get(pieces.size() - 1)), "末片未覆盖文末");
    }

    @Test
    @DisplayName("切片：无换行文本按切片长度减重叠推进，重叠量恰为配置值")
    void shouldAdvanceBySizeMinusOverlapWithoutNewline() {
        String text = "x".repeat(300);

        List<String> pieces = AiDocumentServiceImpl.split(text, 100, 20);

        assertEquals(List.of(100, 100, 100, 60), pieces.stream().map(String::length).toList());
        int total = pieces.stream().mapToInt(String::length).sum();
        assertEquals(text.length() + 20 * (pieces.size() - 1), total, "重叠量应恰为配置值");
    }

    @Test
    @DisplayName("切片：优先在句末标点处断开，且不切断英文单词")
    void shouldPreferSentenceAndWordBoundaries() {
        String text = "Alpha beta gamma. Delta epsilon zeta. Eta theta iota.";

        List<String> pieces = AiDocumentServiceImpl.split(text, 30, 5);

        assertTrue(pieces.size() > 1, "应切出多个切片");
        pieces.forEach(piece -> assertTrue(piece.length() <= 30, "切片超长：" + piece.length()));
        // 除末片外都应断在句末标点之后
        for (int i = 0; i < pieces.size() - 1; i++) {
            assertTrue(pieces.get(i).endsWith("."), "未断在句末标点处：" + pieces.get(i));
        }
        // 任一切片的首尾都不得落在英文单词内部
        int searchFrom = 0;
        for (String piece : pieces) {
            int pos = text.indexOf(piece, searchFrom);
            assertTrue(pos >= 0, "切片不在原文中：" + piece);
            int end = pos + piece.length();
            assertFalse(pos > 0 && isAsciiWordChar(text.charAt(pos - 1)) && isAsciiWordChar(text.charAt(pos)),
                "切片起点落在单词内部：" + piece);
            assertFalse(end < text.length() && isAsciiWordChar(text.charAt(end - 1)) && isAsciiWordChar(text.charAt(end)),
                "切片终点落在单词内部：" + piece);
            searchFrom = pos;
        }
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}