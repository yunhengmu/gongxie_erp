package org.dromara.ai.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索「重排融合」纯函数工具：把「向量召回」与「文本重排」两路结果按所选模式融合为最终排序。
 *
 * <p>职责单一、无副作用、不持有状态，便于单测；调用方（{@code AiVectorServiceImpl}）负责发起重排请求、
 * 降级兜底与按 topK 截断。</p>
 *
 * <p><b>三种模式</b>：</p>
 * <ul>
 *   <li>{@link Mode#REPLACE}：直接用重排顺序与重排分（行为与早期「纯替换」策略一致）；</li>
 *   <li>{@link Mode#RANK_RRF}：对「向量排名」与「重排排名」做倒数排名融合（RRF，第 r 名计
 *       {@code 1/(k+r)}，两路相加），既保留向量头部又吸收重排的语义判断；</li>
 *   <li>{@link Mode#SCORE_FUSION}：把两路分数各自 min-max 归一化到 [0,1] 后按
 *       {@code alpha*重排 + (1-alpha)*向量} 加权，规避两路量纲不可比的问题。</li>
 * </ul>
 *
 * <p><b>边界</b>：空集合返回空；单条 / 全等分数稳健；{@code alpha} 自动夹到 [0,1]；
 * {@code rrfK ≤ 0} 时回退 {@link #DEFAULT_RRF_K}。输出始终按融合分降序，并列时保持原下标顺序（稳定排序）。</p>
 *
 * @author BizBuddy
 */
public final class AiRankFusion {

    /**
     * RRF 默认平滑常数（经典取值 60）。
     */
    public static final int DEFAULT_RRF_K = 60;

    /**
     * score_fusion 默认权重（重排权重 0.5）。
     */
    public static final double DEFAULT_ALPHA = 0.5;

    /**
     * 归一化时判定「最大值≈最小值」的容差，避免除零。
     */
    private static final double EPSILON = 1e-12;

    private AiRankFusion() {
    }

    /**
     * 融合模式。
     */
    public enum Mode {

        /**
         * 用重排顺序与重排分直接替换（早期「纯替换」策略）。
         */
        REPLACE,

        /**
         * 对「向量排名」与「重排排名」做倒数排名融合（RRF）。
         */
        RANK_RRF,

        /**
         * 两路分数归一化后加权融合。
         */
        SCORE_FUSION;

        /**
         * 解析模式字符串（大小写与空白不敏感）。
         *
         * <p>别名：{@code rrf} → {@link #RANK_RRF}，{@code fusion} → {@link #SCORE_FUSION}；
         * 空值 / 未知取值 / {@code replace} → {@link #REPLACE}。</p>
         *
         * @param value 模式字符串
         * @return 对应模式（无法识别时回退 {@link #REPLACE}）
         */
        public static Mode from(String value) {
            if (value == null) {
                return REPLACE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "rank_rrf", "rrf" -> RANK_RRF;
                case "score_fusion", "fusion" -> SCORE_FUSION;
                default -> REPLACE;
            };
        }
    }

    /**
     * 融合结果条目。
     *
     * @param index 原始候选下标
     * @param score 融合分（量纲随模式：REPLACE=重排分 / RRF=RRF 分 / SCORE_FUSION=归一化加权分）
     */
    public record Fused(int index, double score) {
    }

    /**
     * 按业务主键融合的条目（用于向量 + BM25 两路召回）。
     *
     * @param key   业务主键（如切片主键）
     * @param score RRF 融合分
     */
    public record FusedKey(String key, double score) {
    }

    /**
     * 倒数排名融合（RRF）：把两路「按主键排序」的召回结果合并为统一排序。
     *
     * <p>第 r 名计 {@code 1/(k+r)}，两路相加；同一路内重复主键只按首次出现计。
     * 输出按融合分降序；分数相同时保持「首次出现」先后（稳定）。</p>
     *
     * @param first  第一路主键（按相关度降序，如向量召回）
     * @param second 第二路主键（按相关度降序，如 BM25 召回）
     * @param rrfK   平滑常数（≤0 时取 {@link #DEFAULT_RRF_K}）
     * @param limit  最多返回条数（≤0 表示不截断）
     * @return 融合后的主键与分数（降序）
     */
    public static List<FusedKey> reciprocalRankFusion(List<String> first, List<String> second, int rrfK, int limit) {
        int k = rrfK > 0 ? rrfK : DEFAULT_RRF_K;
        Map<String, double[]> merged = new LinkedHashMap<>();
        accumulateRanks(first, k, merged);
        accumulateRanks(second, k, merged);
        List<FusedKey> fused = new ArrayList<>(merged.size());
        for (Map.Entry<String, double[]> entry : merged.entrySet()) {
            fused.add(new FusedKey(entry.getKey(), entry.getValue()[0]));
        }
        fused.sort((left, right) -> {
            int byScore = Double.compare(right.score(), left.score());
            if (byScore != 0) {
                return byScore;
            }
            return Double.compare(merged.get(left.key())[1], merged.get(right.key())[1]);
        });
        if (limit > 0 && fused.size() > limit) {
            return new ArrayList<>(fused.subList(0, limit));
        }
        return fused;
    }

    /**
     * 累计一路召回的倒数排名分。
     *
     * @param keys   主键列表（已降序）
     * @param k      RRF 常数
     * @param merged 主键 → [融合分, 首次出现序号]
     */
    private static void accumulateRanks(List<String> keys, int k, Map<String, double[]> merged) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int rank = 1;
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            double[] item = merged.computeIfAbsent(key, ignored -> new double[]{0.0, merged.size()});
            item[0] += 1.0 / (k + rank);
            rank++;
        }
    }

    /**
     * 融合两路结果为「按融合分降序」的下标序。
     *
     * @param vectorScores 向量召回分（按原始召回顺序，假定已降序）
     * @param rerankDocs   重排条目（原始下标 + 重排分，假定已按重排分降序）
     * @param mode         融合模式；为 {@code null} 时按 {@link Mode#REPLACE}
     * @param alpha        {@link Mode#SCORE_FUSION} 的重排权重（自动夹到 [0,1]）
     * @param rrfK         {@link Mode#RANK_RRF} 的平滑常数（≤0 时取 {@link #DEFAULT_RRF_K}）
     * @return 融合条目列表（融合分降序）；向量候选为空时返回空列表
     */
    public static List<Fused> fuse(List<Double> vectorScores, List<AiRerankClient.RankedDoc> rerankDocs,
                                   Mode mode, double alpha, int rrfK) {
        int size = vectorScores == null ? 0 : vectorScores.size();
        if (size == 0) {
            return List.of();
        }
        List<AiRerankClient.RankedDoc> docs = rerankDocs == null ? List.of() : rerankDocs;
        Mode effectiveMode = mode == null ? Mode.REPLACE : mode;
        return switch (effectiveMode) {
            case RANK_RRF -> fuseByRank(size, docs, rrfK);
            case SCORE_FUSION -> fuseByScore(vectorScores, size, docs, alpha);
            case REPLACE -> replaceOrder(docs);
        };
    }

    /**
     * REPLACE：直接采用重排顺序与重排分（跳过非法下标）。
     *
     * @param docs 重排条目（已降序）
     * @return 融合条目
     */
    private static List<Fused> replaceOrder(List<AiRerankClient.RankedDoc> docs) {
        List<Fused> result = new ArrayList<>(docs.size());
        for (AiRerankClient.RankedDoc doc : docs) {
            result.add(new Fused(doc.index(), doc.score()));
        }
        return result;
    }

    /**
     * RANK_RRF：按「向量排名」与「重排排名」两路倒数排名融合。
     *
     * <p>第 i 个候选（0 起）的向量排名为 {@code i+1}；重排排名为其在 {@code docs} 中的次序。
     * 未被重排命中的候选只计向量一路。分数越高越靠前。</p>
     *
     * @param size 候选数量
     * @param docs 重排条目（已降序）
     * @param rrfK 平滑常数
     * @return 融合条目（降序）
     */
    private static List<Fused> fuseByRank(int size, List<AiRerankClient.RankedDoc> docs, int rrfK) {
        int k = rrfK > 0 ? rrfK : DEFAULT_RRF_K;
        int[] rerankRank = new int[size];
        int rank = 1;
        for (AiRerankClient.RankedDoc doc : docs) {
            int index = doc.index();
            if (index >= 0 && index < size && rerankRank[index] == 0) {
                rerankRank[index] = rank++;
            }
        }
        List<Fused> fused = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double score = 1.0 / (k + (i + 1));
            if (rerankRank[i] > 0) {
                score += 1.0 / (k + rerankRank[i]);
            }
            fused.add(new Fused(i, score));
        }
        fused.sort(Comparator.comparingDouble(Fused::score).reversed());
        return fused;
    }

    /**
     * SCORE_FUSION：两路分数各自 min-max 归一化后按 {@code alpha} 加权。
     *
     * <p>向量分对全部候选归一化；重排分只对「被重排命中的候选」归一化，未命中者重排归一化分记 0。
     * 某一路的最大值≈最小值时，该路归一化分统一记 1。最终分越高越靠前。</p>
     *
     * @param vectorScores 向量召回分
     * @param size         候选数量
     * @param docs         重排条目（已降序）
     * @param alpha        重排权重
     * @return 融合条目（降序）
     */
    private static List<Fused> fuseByScore(List<Double> vectorScores, int size,
                                           List<AiRerankClient.RankedDoc> docs, double alpha) {
        double weight = clamp01(alpha);
        double[] rerankScore = new double[size];
        boolean[] present = new boolean[size];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (AiRerankClient.RankedDoc doc : docs) {
            int index = doc.index();
            if (index >= 0 && index < size && !present[index]) {
                present[index] = true;
                rerankScore[index] = doc.score();
                min = Math.min(min, doc.score());
                max = Math.max(max, doc.score());
            }
        }

        double[] vectorNorm = minMax(vectorScores, size);
        List<Fused> fused = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double rerankNorm = present[i] ? normalize(rerankScore[i], min, max) : 0.0;
            double score = weight * rerankNorm + (1 - weight) * vectorNorm[i];
            fused.add(new Fused(i, score));
        }
        fused.sort(Comparator.comparingDouble(Fused::score).reversed());
        return fused;
    }

    /**
     * 对全部候选的向量分做 min-max 归一化。
     *
     * @param vectorScores 向量召回分
     * @param size         候选数量
     * @return 归一化分数组
     */
    private static double[] minMax(List<Double> vectorScores, int size) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            double value = vectorScores.get(i) == null ? 0.0 : vectorScores.get(i);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        double[] norm = new double[size];
        for (int i = 0; i < size; i++) {
            double value = vectorScores.get(i) == null ? 0.0 : vectorScores.get(i);
            norm[i] = normalize(value, min, max);
        }
        return norm;
    }

    /**
     * 单个值归一化到 [0,1]。
     *
     * @param value 值
     * @param min   最小值
     * @param max   最大值
     * @return 归一化结果；最大值≈最小值时返回 1
     */
    private static double normalize(double value, double min, double max) {
        if (max - min <= EPSILON) {
            return 1.0;
        }
        return (value - min) / (max - min);
    }

    /**
     * 把权重夹到 [0,1]。
     *
     * @param alpha 原权重
     * @return 夹取结果
     */
    private static double clamp01(double alpha) {
        if (Double.isNaN(alpha)) {
            return DEFAULT_ALPHA;
        }
        return Math.max(0.0, Math.min(1.0, alpha));
    }

}