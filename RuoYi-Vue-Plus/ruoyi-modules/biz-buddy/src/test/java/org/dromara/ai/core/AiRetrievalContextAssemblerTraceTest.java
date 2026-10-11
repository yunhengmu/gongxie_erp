package org.dromara.ai.core;

import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AiRetrievalContextAssembler} 溯源元数据透传测试：去重保留首条、相邻合并取起始块、截断拷贝透传。
 */
@DisplayName("检索结果装配：溯源元数据透传")
class AiRetrievalContextAssemblerTraceTest {

    private static AiRetrievalHitVo hit(Long docId, String docName, Integer chunkIndex, Double score, String content,
                                        String headingPath, Integer pageNo, Integer charStart, Integer charEnd) {
        AiRetrievalHitVo vo = new AiRetrievalHitVo();
        vo.setDocId(docId);
        vo.setDocName(docName);
        vo.setChunkIndex(chunkIndex);
        vo.setScore(score);
        vo.setContent(content);
        vo.setHeadingPath(headingPath);
        vo.setPageNo(pageNo);
        vo.setCharStart(charStart);
        vo.setCharEnd(charEnd);
        return vo;
    }

    @Test
    @DisplayName("去重保留首条命中，其溯源元数据一并保留")
    void shouldKeepFirstHitMetadataOnDedup() {
        AiRetrievalHitVo first = hit(1L, "手册", 0, 0.9, "考勤与请假管理规定的完整正文。", "第1章", 1, 0, 20);
        AiRetrievalHitVo second = hit(1L, "手册", 1, 0.8, "考勤与请假管理规定的完整正文。", "第2章", 2, 20, 40);
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(first, second), new AiRetrievalContextAssembler.Options(true, false, 1, 0.9, 6000, 6000));

        assertEquals(1, result.size());
        assertEquals("第1章", result.get(0).getHeadingPath());
        assertEquals(1, result.get(0).getPageNo());
        assertEquals(0, result.get(0).getCharStart());
        assertEquals(20, result.get(0).getCharEnd());
    }

    @Test
    @DisplayName("相邻合并取起始块（切片号最小）的章节 / 页码，字符区间取并集")
    void shouldMergeMetadataFromStartBlock() {
        AiRetrievalHitVo later = hit(11L, "手册", 3, 0.9, "第三段内容", "第3章", 5, 30, 40);
        AiRetrievalHitVo earlier = hit(11L, "手册", 2, 0.5, "第二段内容", "第2章", 4, 10, 20);
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(later, earlier), new AiRetrievalContextAssembler.Options(false, true, 1, 0.9, 6000, 6000));

        assertEquals(1, result.size());
        AiRetrievalHitVo merged = result.get(0);
        assertEquals(2, merged.getChunkIndex());
        assertEquals(3, merged.getChunkIndexEnd());
        assertEquals("第2章", merged.getHeadingPath());
        assertEquals(4, merged.getPageNo());
        assertEquals(10, merged.getCharStart());
        assertEquals(40, merged.getCharEnd());
    }

    @Test
    @DisplayName("字符上限截断首条时，拷贝仍透传溯源元数据")
    void shouldPassMetadataThroughTruncationCopy() {
        AiRetrievalHitVo first = hit(1L, "A", 0, 0.9, "0123456789ABCDEF", "第1章", 7, 0, 16);
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(first), new AiRetrievalContextAssembler.Options(false, false, 0, 0.9, 10, 6000));

        assertEquals(1, result.size());
        assertEquals("0123456789", result.get(0).getContent());
        assertEquals("第1章", result.get(0).getHeadingPath());
        assertEquals(7, result.get(0).getPageNo());
        assertEquals(0, result.get(0).getCharStart());
        assertEquals(16, result.get(0).getCharEnd());
    }

}