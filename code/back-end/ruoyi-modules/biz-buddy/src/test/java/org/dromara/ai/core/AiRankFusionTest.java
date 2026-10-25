package org.dromara.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiRankFusion} 单元测试：三种融合模式、边界与空集、模式解析。
 */
@DisplayName("重排融合纯函数")
class AiRankFusionTest {

    private static List<Double> scores(double... values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static List<AiRerankClient.RankedDoc> docs(Object... pairs) {
        List<AiRerankClient.RankedDoc> list = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            int index = ((Number) pairs[i]).intValue();
            double score = ((Number) pairs[i + 1]).doubleValue();
            list.add(new AiRerankClient.RankedDoc(index, score));
        }
        return list;
    }

    private static List<Integer> indices(List<AiRankFusion.Fused> fused) {
        return fused.stream().map(AiRankFusion.Fused::index).toList();
    }

    @Test
    @DisplayName("REPLACE：直接采用重排顺序与重排分")
    void shouldReplaceByRerankOrder() {
        List<AiRankFusion.Fused> fused = AiRankFusion.fuse(
            scores(0.9, 0.8), docs(1, 0.95, 0, 0.3), AiRankFusion.Mode.REPLACE, 0.5, 60);
        assertEquals(List.of(1, 0), indices(fused));
        assertEquals(0.95, fused.get(0).score());
        assertEquals(0.3, fused.get(1).score());
    }

    @Test
    @DisplayName("RANK_RRF：向量头部与重排头部都靠前，非对称排名不抵消")
    void shouldFuseByRankRrf() {
        // 向量排名 1,2,3；重排排名：idx1→1、idx2→2、idx0→3
        List<AiRankFusion.Fused> fused = AiRankFusion.fuse(
            scores(0.95, 0.7, 0.5), docs(1, 0.9, 2, 0.6, 0, 0.1),
            AiRankFusion.Mode.RANK_RRF, 0.5, 60);
        // i1=1/62+1/61 最高；i0=1/61+1/63 次之；i2=1/63+1/62 最低
        assertEquals(List.of(1, 0, 2), indices(fused));
        assertTrue(fused.get(0).score() > fused.get(1).score());
        assertTrue(fused.get(1).score() > fused.get(2).score());
    }

    @Test
    @DisplayName("SCORE_FUSION：alpha 越高越信重排，alpha=0 即纯向量序")
    void shouldFuseByScore() {
        List<Double> vector = scores(0.95, 0.7, 0.5);
        List<AiRerankClient.RankedDoc> rerank = docs(1, 0.95, 0, 0.9, 2, 0.5);
        // alpha=0.5：idx0 归一化(向量1.0, 重排0.889)→0.944；idx1(0.444,1.0)→0.722；idx2→0
        assertEquals(List.of(0, 1, 2), indices(AiRankFusion.fuse(vector, rerank, AiRankFusion.Mode.SCORE_FUSION, 0.5, 60)));
        // alpha=1：纯重排序
        assertEquals(List.of(1, 0, 2), indices(AiRankFusion.fuse(vector, rerank, AiRankFusion.Mode.SCORE_FUSION, 1.0, 60)));
        // alpha=0：纯向量序
        assertEquals(List.of(0, 1, 2), indices(AiRankFusion.fuse(vector, rerank, AiRankFusion.Mode.SCORE_FUSION, 0.0, 60)));
    }

    @Test
    @DisplayName("边界：空集返回空；单条稳健；alpha 越界被夹取；rrfK≤0 回退默认")
    void shouldHandleBoundaries() {
        assertTrue(AiRankFusion.fuse(List.of(), List.of(), AiRankFusion.Mode.RANK_RRF, 0.5, 60).isEmpty());
        assertTrue(AiRankFusion.fuse(null, null, AiRankFusion.Mode.SCORE_FUSION, 0.5, 60).isEmpty());
        assertEquals(List.of(0), indices(AiRankFusion.fuse(scores(0.5), docs(0, 0.9), AiRankFusion.Mode.SCORE_FUSION, 0.5, 60)));
        // 全等分数：归一化统一为 1，叠加后仍可按向量序稳定输出
        assertEquals(List.of(0, 1), indices(AiRankFusion.fuse(scores(0.5, 0.5), docs(1, 0.2, 0, 0.2),
            AiRankFusion.Mode.SCORE_FUSION, 0.5, 60)));
        // alpha=-5 夹到 0 → 纯向量序
        assertEquals(List.of(0, 1), indices(AiRankFusion.fuse(scores(0.9, 0.1), docs(1, 0.9, 0, 0.1),
            AiRankFusion.Mode.SCORE_FUSION, -5, 60)));
        // rrfK<=0 回退 60：等价于显式传 60
        assertEquals(
            indices(AiRankFusion.fuse(scores(0.9, 0.8), docs(1, 0.9, 0, 0.1), AiRankFusion.Mode.RANK_RRF, 0.5, 0)),
            indices(AiRankFusion.fuse(scores(0.9, 0.8), docs(1, 0.9, 0, 0.1), AiRankFusion.Mode.RANK_RRF, 0.5, 60)));
    }

    @Test
    @DisplayName("模式解析：别名与未知取值")
    void shouldParseMode() {
        assertEquals(AiRankFusion.Mode.RANK_RRF, AiRankFusion.Mode.from("rank_rrf"));
        assertEquals(AiRankFusion.Mode.RANK_RRF, AiRankFusion.Mode.from(" RRF "));
        assertEquals(AiRankFusion.Mode.SCORE_FUSION, AiRankFusion.Mode.from("score_fusion"));
        assertEquals(AiRankFusion.Mode.SCORE_FUSION, AiRankFusion.Mode.from("Fusion"));
        assertEquals(AiRankFusion.Mode.REPLACE, AiRankFusion.Mode.from("replace"));
        assertEquals(AiRankFusion.Mode.REPLACE, AiRankFusion.Mode.from(null));
        assertEquals(AiRankFusion.Mode.REPLACE, AiRankFusion.Mode.from("unknown"));
    }

    @Test
    @DisplayName("向量 + BM25：两路命中叠加得分，双路命中优先于单路")
    void shouldFuseDenseAndBm25() {
        List<AiRankFusion.FusedKey> fused = AiRankFusion.reciprocalRankFusion(
            List.of("a", "b", "c"), List.of("c", "d"), 60, 10);
        assertEquals(List.of("c", "a", "b", "d"),
            fused.stream().map(AiRankFusion.FusedKey::key).toList());
        // c：两路各第 3/1 名；a：仅向量第 1 名
        assertTrue(fused.get(0).score() > fused.get(1).score());
        assertTrue(fused.get(1).score() > fused.get(2).score());
    }

    @Test
    @DisplayName("向量 + BM25：单路为空时退化为另一路顺序；重复主键只计首次；按 limit 截断")
    void shouldHandleHybridBoundaries() {
        List<AiRankFusion.FusedKey> onlyBm25 = AiRankFusion.reciprocalRankFusion(
            List.of(), List.of("x", "y"), 60, 10);
        assertEquals(List.of("x", "y"), onlyBm25.stream().map(AiRankFusion.FusedKey::key).toList());

        List<AiRankFusion.FusedKey> bothEmpty = AiRankFusion.reciprocalRankFusion(List.of(), List.of(), 60, 10);
        assertTrue(bothEmpty.isEmpty());

        List<AiRankFusion.FusedKey> dedup = AiRankFusion.reciprocalRankFusion(
            List.of("a", "a", "b"), List.of(), 60, 10);
        assertEquals(List.of("a", "b"), dedup.stream().map(AiRankFusion.FusedKey::key).toList());

        assertEquals(2, AiRankFusion.reciprocalRankFusion(
            List.of("a", "b", "c"), List.of(), 60, 2).size());
    }

}