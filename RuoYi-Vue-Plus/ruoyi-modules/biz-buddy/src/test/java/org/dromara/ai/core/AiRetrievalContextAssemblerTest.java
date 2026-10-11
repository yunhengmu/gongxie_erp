package org.dromara.ai.core;

import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiRetrievalContextAssembler} 单元测试：去重（子串 / Jaccard 边界）、相邻合并（窗口 / 跨文档 / 分数取值）、
 * 字符上限（按相关度保留）与空集 / 单条边界。
 */
@DisplayName("检索结果上下文装配器")
class AiRetrievalContextAssemblerTest {

    private static final String BASE = "这是一段用于测试去重判据的知识库片段内容示例文本";
    private static final String NEAR_DUP = "这是一段用于测试去重判据的知识库片段内容示例文字";
    private static final String BELOW_THRESHOLD = "这是一段用于测试去重判据的知识库片段内容示例图库";

    private static AiRetrievalHitVo hit(Long docId, String docName, Integer chunkIndex, Double score, String content) {
        AiRetrievalHitVo vo = new AiRetrievalHitVo();
        vo.setDocId(docId);
        vo.setDocName(docName);
        vo.setChunkIndex(chunkIndex);
        vo.setScore(score);
        vo.setContent(content);
        return vo;
    }

    /** 去重开启、合并关闭、上限充足 */
    private static AiRetrievalContextAssembler.Options dedupOnly() {
        return new AiRetrievalContextAssembler.Options(true, false, 1, 0.9, 6000, 6000);
    }

    /** 合并开启、去重关闭 */
    private static AiRetrievalContextAssembler.Options mergeOnly(int window) {
        return new AiRetrievalContextAssembler.Options(false, true, window, 0.9, 6000, 6000);
    }

    /** 全部关闭（仅字符上限） */
    private static AiRetrievalContextAssembler.Options capOnly(int maxChars) {
        return capOnly(maxChars, 6000);
    }

    /** 全部关闭（字符上限 + Token 预算双约束） */
    private static AiRetrievalContextAssembler.Options capOnly(int maxChars, int maxTokens) {
        return new AiRetrievalContextAssembler.Options(false, false, 0, 0.9, maxChars, maxTokens);
    }

    @Test
    @DisplayName("空集返回空列表")
    void shouldReturnEmptyForEmptyInput() {
        assertTrue(AiRetrievalContextAssembler.assemble(null, dedupOnly()).isEmpty());
        assertTrue(AiRetrievalContextAssembler.assemble(List.of(), dedupOnly()).isEmpty());
    }

    @Test
    @DisplayName("单条原样返回")
    void shouldReturnSingleHit() {
        AiRetrievalHitVo single = hit(1L, "手册", 0, 0.7, BASE);
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(List.of(single), mergeOnly(1));
        assertEquals(1, result.size());
        assertEquals(BASE, result.get(0).getContent());
        assertEquals("手册", result.get(0).getDocName());
        assertNull(result.get(0).getChunkIndexEnd());
    }

    @Test
    @DisplayName("去重：互为子串的短正文只保留首个")
    void shouldDedupBySubstring() {
        String longText = "关于员工考勤与请假管理的规定：第一条规定每日打卡时间，第二条规定请假审批流程。";
        String subText = "第一条规定每日打卡时间，第二条规定请假审批流程。";
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "手册", 0, 0.9, longText), hit(2L, "手册", 1, 0.8, subText)), dedupOnly());
        assertEquals(1, result.size());
        assertEquals(longText, result.get(0).getContent());
    }

    @Test
    @DisplayName("去重：Jaccard 达阈值判重、低于阈值保留")
    void shouldDedupByJaccardBoundary() {
        List<AiRetrievalHitVo> dup = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "手册", 0, 0.9, BASE), hit(2L, "手册", 1, 0.8, NEAR_DUP)), dedupOnly());
        assertEquals(1, dup.size());

        List<AiRetrievalHitVo> kept = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "手册", 0, 0.9, BASE), hit(2L, "手册", 1, 0.8, BELOW_THRESHOLD)), dedupOnly());
        assertEquals(2, kept.size());
    }

    @Test
    @DisplayName("去重：内容不同的命中全部保留")
    void shouldKeepDistinctContent() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "手册", 0, 0.9, "考勤管理规定正文。"), hit(2L, "制度", 0, 0.8, "差旅报销标准正文。")),
            dedupOnly());
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("合并：窗口 1 内同一文档相邻切片合并为一条")
    void shouldMergeAdjacentWithinWindowOne() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(11L, "手册", 3, 0.9, "第三段内容"), hit(11L, "手册", 2, 0.5, "第二段内容")), mergeOnly(1));
        assertEquals(1, result.size());
        AiRetrievalHitVo merged = result.get(0);
        assertEquals(2, merged.getChunkIndex());
        assertEquals(3, merged.getChunkIndexEnd());
        assertEquals(0.9, merged.getScore());
        // 内容按 chunkIndex 升序拼接
        assertEquals("第二段内容\n第三段内容", merged.getContent());
    }

    @Test
    @DisplayName("合并：窗口 0 时不合并相邻切片")
    void shouldNotMergeWhenWindowZero() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(11L, "手册", 2, 0.9, "第二段内容"), hit(11L, "手册", 3, 0.5, "第三段内容")), mergeOnly(0));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("合并：窗口 2 时相隔 2 的切片合并")
    void shouldMergeWithinWindowTwo() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(11L, "手册", 1, 0.9, "第一段"), hit(11L, "手册", 3, 0.5, "第三段")), mergeOnly(2));
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getChunkIndex());
        assertEquals(3, result.get(0).getChunkIndexEnd());
    }

    @Test
    @DisplayName("合并：不同文档即便切片号相邻也不合并")
    void shouldNotMergeAcrossDocuments() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(11L, "手册", 2, 0.9, "手册内容"), hit(12L, "制度", 3, 0.5, "制度内容")), mergeOnly(1));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("字符上限：按相关度优先保留（输入序），超限即停")
    void shouldCapByRelevance() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "A", 0, 0.9, "AAAAAA"), hit(2L, "B", 0, 0.8, "BBBBBB"), hit(3L, "C", 0, 0.7, "CCCCCC")),
            capOnly(10));
        assertEquals(1, result.size());
        assertEquals("AAAAAA", result.get(0).getContent());
    }

    @Test
    @DisplayName("字符上限：首条（最高相关）超限时截断而非丢弃")
    void shouldTruncateFirstFragment() {
        AiRetrievalHitVo first = hit(1L, "A", 0, 0.9, "0123456789ABCDEF");
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(List.of(first), capOnly(10));
        assertEquals(1, result.size());
        assertEquals("0123456789", result.get(0).getContent());
        // 入参对象不被修改
        assertEquals("0123456789ABCDEF", first.getContent());
    }

    @Test
    @DisplayName("Token 预算：折算字符上限更小者生效，按相关度优先保留")
    void shouldCapByTokenBudget() {
        // 绝对字符上限充足（6000），Token 预算 10 → 生效字符上限 10：仅保留首条（6 字）
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "A", 0, 0.9, "AAAAAA"), hit(2L, "B", 0, 0.8, "BBBBBB")), capOnly(6000, 10));
        assertEquals(1, result.size());
        assertEquals("AAAAAA", result.get(0).getContent());
    }

    @Test
    @DisplayName("Token 预算：首条（最高相关）超预算时截断而非丢弃")
    void shouldTruncateFirstFragmentByTokenBudget() {
        AiRetrievalHitVo first = hit(1L, "A", 0, 0.9, "0123456789ABCDEF");
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(List.of(first), capOnly(6000, 10));
        assertEquals(1, result.size());
        assertEquals("0123456789", result.get(0).getContent());
    }

    @Test
    @DisplayName("生效字符上限：取绝对字符上限与 Token 预算折算值的较小者")
    void shouldComputeEffectiveMaxChars() {
        assertEquals(10, AiRetrievalContextAssembler.effectiveMaxChars(capOnly(10, 6000)));
        assertEquals(10, AiRetrievalContextAssembler.effectiveMaxChars(capOnly(6000, 10)));
        assertEquals(3000, AiRetrievalContextAssembler.effectiveMaxChars(capOnly(6000, 3000)));
        // 两者均未配置时回退默认 6000
        assertEquals(6000, AiRetrievalContextAssembler.effectiveMaxChars(capOnly(0, 0)));
    }

    @Test
    @DisplayName("选项为空时使用默认配置（去重开启）")
    void shouldUseDefaultsWhenOptionsNull() {
        List<AiRetrievalHitVo> result = AiRetrievalContextAssembler.assemble(
            List.of(hit(1L, "手册", 0, 0.9, BASE), hit(2L, "手册", 1, 0.8, BASE)), null);
        assertEquals(1, result.size());
    }
}