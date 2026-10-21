package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiChunk;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.core.document.AiDocumentBatchRunner;
import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkContextVo;
import org.dromara.ai.domain.vo.AiDocumentBatchStatusVo;
import org.dromara.ai.domain.vo.AiDocumentBatchVo;
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
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyCollection;
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

    @Mock
    private AiDocumentBatchRunner batchRunner;

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
    @DisplayName("分页查询文档：按 ossId 现算文件访问地址")
    void shouldFillFileUrlOnQueryPageList() {
        AiDocumentVo vo = new AiDocumentVo();
        vo.setOssId(5L);
        Page<AiDocumentVo> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(vo)));
        page.setTotal(1L);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);
        when(ossService.selectUrlByIds("5")).thenReturn("http://presigned/a");

        PageResult<AiDocumentVo> result = service.queryPageList(new AiDocumentBo(), new PageQuery(1, 10));

        assertEquals("http://presigned/a", result.getRows().iterator().next().getFileUrl());
    }

    @Test
    @DisplayName("按主键查询文档")
    void shouldQueryById() {
        AiDocumentVo vo = new AiDocumentVo();
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        assertSame(vo, service.queryById(1L));
    }

    @Test
    @DisplayName("按主键查询文档：按 ossId 现算文件访问地址")
    void shouldFillFileUrlOnQueryById() {
        AiDocumentVo vo = new AiDocumentVo();
        vo.setOssId(5L);
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        when(ossService.selectUrlByIds("5")).thenReturn("http://presigned/a,http://b");

        AiDocumentVo result = service.queryById(1L);

        assertEquals("http://presigned/a", result.getFileUrl());
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
    @DisplayName("新增文档：校验文件、校验同名并插入（file_url 不落库）")
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
        assertNull(entity.getFileUrl());
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
            ps.when(() -> AiDocumentParser.parse(any(byte[].class), eq("txt")))
                .thenReturn(List.of(new AiDocumentParser.ParsedBlock("hello world", 0, null, null)));

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
            ps.when(() -> AiDocumentParser.parse(any(byte[].class), eq("txt")))
                .thenReturn(List.of(new AiDocumentParser.ParsedBlock("  ", 0, null, null)));

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
    @DisplayName("拼块与块定位：跨块切片的元数据取起始块")
    void shouldLocateStartBlockForCrossBlockSlice() {
        AiDocumentServiceImpl.TextAndRanges parsed = AiDocumentServiceImpl.joinBlocks(List.of(
            new AiDocumentParser.ParsedBlock("AAA", 0, "第1章", null),
            new AiDocumentParser.ParsedBlock("BBB", 0, "第2章", 3)));

        assertEquals("AAA\nBBB", parsed.text());
        assertEquals(0, parsed.ranges().get(0).start());
        assertEquals(3, parsed.ranges().get(0).end());
        assertEquals(4, parsed.ranges().get(1).start());
        // 起点落在第 1 块，即便切片跨越到第 2 块也取起始块
        assertEquals("第1章", AiDocumentServiceImpl.locateBlock(parsed.ranges(), 2).headingPath());
        assertEquals("第2章", AiDocumentServiceImpl.locateBlock(parsed.ranges(), 4).headingPath());
        assertEquals(3, AiDocumentServiceImpl.locateBlock(parsed.ranges(), 4).pageNo());
    }

    @Test
    @DisplayName("切片附带全文中的字符区间")
    void shouldAttachCharRangeToSlices() {
        List<AiDocumentServiceImpl.SlicePiece> pieces = AiDocumentServiceImpl.splitPieces("abcdefghij", 4, 0);

        assertEquals(3, pieces.size());
        assertEquals(0, pieces.get(0).start());
        assertEquals(4, pieces.get(0).end());
        assertEquals(8, pieces.get(2).start());
        assertEquals(10, pieces.get(2).end());
    }

    @Test
    @DisplayName("解析文档：切片写入章节路径 / 页码 / 字符区间")
    void shouldWriteChunkMetadata() {
        AiDocument doc = document(1L, 2L, "txt", 5L, "0");
        when(baseMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb(2L, 5, 0));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(ossService.selectInternalUrlByIds("5")).thenReturn("http://minio/a");
        when(baseMapper.selectVoById(1L)).thenReturn(new AiDocumentVo());
        try (MockedStatic<HttpUtil> hs = mockStatic(HttpUtil.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            hs.when(() -> HttpUtil.downloadBytes("http://minio/a")).thenReturn(new byte[]{1});
            ps.when(() -> AiDocumentParser.parse(any(byte[].class), eq("txt"))).thenReturn(List.of(
                new AiDocumentParser.ParsedBlock("AAAA", 1, "第1章", null),
                new AiDocumentParser.ParsedBlock("BBBB", 0, "第2章", 2)));

            service.parse(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AiChunk>> captor = ArgumentCaptor.forClass(List.class);
            verify(chunkMapper).insertBatch(captor.capture());
            List<AiChunk> chunks = captor.getValue();
            assertEquals(2, chunks.size());
            assertEquals("第1章", chunks.get(0).getHeadingPath());
            assertNull(chunks.get(0).getPageNo());
            assertEquals(0, chunks.get(0).getCharStart());
            assertEquals(5, chunks.get(0).getCharEnd());
            assertEquals("第2章", chunks.get(1).getHeadingPath());
            assertEquals(2, chunks.get(1).getPageNo());
            assertEquals(5, chunks.get(1).getCharStart());
            assertEquals(9, chunks.get(1).getCharEnd());
        }
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

    // ==================== 参考来源定位（按切片序号） ====================

    @Test
    @DisplayName("切片定位：返回命中切片与前后邻居")
    void shouldReturnChunkContextWithNeighbors() {
        when(baseMapper.selectById(1L)).thenReturn(document(1L, 2L, "txt", 5L, "2"));
        when(knowledgeBaseService.filterVisibleKbIds(anyCollection(), eq(9L))).thenReturn(List.of(2L));
        when(chunkMapper.selectVoList(any())).thenReturn(List.of(chunkVo(1), chunkVo(2), chunkVo(3)));

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);

            AiChunkContextVo context = service.queryChunkContext(1L, 2, 1);

            assertEquals(2, context.getTarget().getChunkIndex());
            assertEquals(1, context.getBefore().size());
            assertEquals(1, context.getAfter().size());
        }
    }

    @Test
    @DisplayName("切片定位：序号越界时目标为空而不报错（文档重新解析后来源可能失效）")
    void shouldReturnEmptyTargetWhenChunkIndexMissing() {
        when(baseMapper.selectById(1L)).thenReturn(document(1L, 2L, "txt", 5L, "2"));
        when(knowledgeBaseService.filterVisibleKbIds(anyCollection(), eq(9L))).thenReturn(List.of(2L));
        when(chunkMapper.selectVoList(any())).thenReturn(List.of(chunkVo(1)));

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);

            AiChunkContextVo context = service.queryChunkContext(1L, 9, 1);

            assertNull(context.getTarget());
            assertEquals(1, context.getBefore().size());
            assertTrue(context.getAfter().isEmpty());
        }
    }

    @Test
    @DisplayName("切片定位：知识库不在可见范围时拒绝（防凭 docId 越权读正文）")
    void shouldRejectInvisibleKnowledgeBaseForChunkContext() {
        when(baseMapper.selectById(1L)).thenReturn(document(1L, 2L, "txt", 5L, "2"));
        when(knowledgeBaseService.filterVisibleKbIds(anyCollection(), eq(9L))).thenReturn(List.of());

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);

            ServiceException ex = assertThrows(ServiceException.class,
                () -> service.queryChunkContext(1L, 1, 1));
            assertEquals("无权访问该知识库文档", ex.getMessage());
        }
    }

    @Test
    @DisplayName("切片定位：文档不存在时拒绝")
    void shouldRejectMissingDocumentForChunkContext() {
        when(baseMapper.selectById(1L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.queryChunkContext(1L, 1, 1));
        assertEquals("文档不存在或已被删除", ex.getMessage());
    }

    @Test
    @DisplayName("切片定位：序号为负时拒绝")
    void shouldRejectNegativeChunkIndex() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.queryChunkContext(1L, -1, 1));
        assertEquals("文档与切片序号不能为空", ex.getMessage());
    }

    // ==================== 批量解析 / 向量化 / 一键入库 ====================

    @Test
    @DisplayName("批量解析：受理待解析文档，正在解析与不存在的文档归入跳过")
    void shouldSubmitParseBatchAndSkipBusyOrMissing() {
        when(baseMapper.selectById(1L)).thenReturn(document(1L, 2L, "txt", 5L, "0"));
        when(baseMapper.selectById(2L)).thenReturn(document(2L, 2L, "txt", 5L, "1"));
        when(baseMapper.selectById(3L)).thenReturn(null);
        when(baseMapper.update(any(AiDocument.class), any())).thenReturn(1);

        AiDocumentBatchVo result = service.submitParseBatch(List.of(1L, 2L, 3L));

        assertEquals(List.of(1L), result.getAccepted());
        assertEquals(2, result.getSkipped().size());
        assertEquals("正在解析中，已跳过", result.getSkipped().get(0).getReason());
        assertEquals("文档不存在或已被删除", result.getSkipped().get(1).getReason());
        verify(batchRunner, times(1)).submit(any());
    }

    @Test
    @DisplayName("批量解析：状态占位失败（并发抢不到）时归入跳过且不入队")
    void shouldSkipParseWhenClaimFails() {
        when(baseMapper.selectById(1L)).thenReturn(document(1L, 2L, "txt", 5L, "0"));
        when(baseMapper.update(any(AiDocument.class), any())).thenReturn(0);

        AiDocumentBatchVo result = service.submitParseBatch(List.of(1L));

        assertTrue(result.getAccepted().isEmpty());
        assertEquals("正在解析中，已跳过", result.getSkipped().get(0).getReason());
        verify(batchRunner, never()).submit(any());
    }

    @Test
    @DisplayName("批量向量化：未解析与正在向量化的文档跳过，已解析的文档入队")
    void shouldSubmitVectorizeBatchWithGuards() {
        when(baseMapper.selectById(1L)).thenReturn(document(1L, 2L, "txt", 5L, "0"));
        AiDocument running = document(2L, 2L, "txt", 5L, "2");
        running.setVectorStatus("1");
        when(baseMapper.selectById(2L)).thenReturn(running);
        AiDocument ready = document(3L, 2L, "txt", 5L, "2");
        ready.setVectorStatus("0");
        when(baseMapper.selectById(3L)).thenReturn(ready);
        when(baseMapper.update(any(AiDocument.class), any())).thenReturn(1);

        AiDocumentBatchVo result = service.submitVectorizeBatch(List.of(1L, 2L, 3L));

        assertEquals(List.of(3L), result.getAccepted());
        assertEquals("尚未解析完成，请先解析", result.getSkipped().get(0).getReason());
        assertEquals("正在向量化中，已跳过", result.getSkipped().get(1).getReason());
        verify(batchRunner, times(1)).submit(any());
    }

    @Test
    @DisplayName("批量一键入库：解析中/向量化中的文档跳过，其余受理并占位解析状态")
    void shouldSubmitIngestBatchAndSkipBusyDocuments() {
        AiDocument parsing = document(1L, 2L, "txt", 5L, "1");
        when(baseMapper.selectById(1L)).thenReturn(parsing);
        AiDocument vectorizing = document(2L, 2L, "txt", 5L, "2");
        vectorizing.setVectorStatus("1");
        when(baseMapper.selectById(2L)).thenReturn(vectorizing);
        AiDocument ready = document(3L, 2L, "txt", 5L, "2");
        ready.setVectorStatus("0");
        when(baseMapper.selectById(3L)).thenReturn(ready);
        when(baseMapper.update(any(AiDocument.class), any())).thenReturn(1);

        AiDocumentBatchVo result = service.submitIngestBatch(List.of(1L, 2L, 3L));

        assertEquals(List.of(3L), result.getAccepted());
        assertEquals("正在解析中，已跳过", result.getSkipped().get(0).getReason());
        assertEquals("正在向量化中，已跳过", result.getSkipped().get(1).getReason());
        verify(batchRunner, times(1)).submit(any());
    }

    @Test
    @DisplayName("批量提交：去重后超上限被拒，且重复主键只查询一次")
    void shouldDedupeAndRejectOversizedBatch() {
        when(baseMapper.selectById(any())).thenReturn(document(1L, 2L, "txt", 5L, "0"));
        when(baseMapper.update(any(AiDocument.class), any())).thenReturn(1);

        AiDocumentBatchVo result = service.submitParseBatch(List.of(1L, 1L));

        assertEquals(List.of(1L), result.getAccepted());
        verify(baseMapper, times(1)).selectById(any());

        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 51; i++) {
            tooMany.add(i);
        }
        assertThrows(ServiceException.class, () -> service.submitParseBatch(tooMany));
    }

    @Test
    @DisplayName("批量进度：按入参顺序返回状态，已删除的文档忽略")
    void shouldQueryBatchStatusInRequestOrder() {
        AiDocument first = document(2L, 2L, "txt", 5L, "2");
        first.setVectorStatus("1");
        AiDocument second = document(1L, 2L, "txt", 5L, "3");
        second.setErrorMsg("下载失败");
        when(baseMapper.selectByIds(any())).thenReturn(List.of(first, second));

        List<AiDocumentBatchStatusVo> status = service.queryBatchStatus(List.of(1L, 2L, 3L));

        assertEquals(2, status.size());
        assertEquals(1L, status.get(0).getId());
        assertEquals("下载失败", status.get(0).getErrorMsg());
        assertEquals("1", status.get(1).getVectorStatus());
    }

    @Test
    @DisplayName("批量解析入口：已占位为「解析中」的文档跳过「解析中」守卫")
    void shouldParseClaimedDocumentWithoutParsingGuard() {
        AiDocument doc = document(1L, 2L, "txt", 5L, "1");
        when(baseMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeBaseService.getEnabledKnowledgeBase(2L)).thenReturn(kb(2L, 10, 0));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(ossService.selectInternalUrlByIds("5")).thenReturn("http://minio/a");
        when(baseMapper.selectVoById(1L)).thenReturn(new AiDocumentVo());
        try (MockedStatic<HttpUtil> hs = mockStatic(HttpUtil.class);
             MockedStatic<AiDocumentParser> ps = mockStatic(AiDocumentParser.class)) {
            hs.when(() -> HttpUtil.downloadBytes("http://minio/a")).thenReturn(new byte[]{1});
            ps.when(() -> AiDocumentParser.parse(any(byte[].class), eq("txt")))
                .thenReturn(List.of(new AiDocumentParser.ParsedBlock("hello", 0, null, null)));

            assertNotNull(service.parseClaimed(1L));
        }
    }

    /** 构造一条切片视图（仅保留定位相关字段） */
    private AiChunkVo chunkVo(int index) {
        AiChunkVo vo = new AiChunkVo();
        vo.setDocId(1L);
        vo.setKbId(2L);
        vo.setChunkIndex(index);
        vo.setContent("第 " + index + " 段");
        return vo;
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}