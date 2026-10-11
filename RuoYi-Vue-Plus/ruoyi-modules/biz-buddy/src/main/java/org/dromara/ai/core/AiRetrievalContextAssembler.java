package org.dromara.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索结果上下文装配器（P0-③）：对「已按相关度降序排列的检索命中」做确定性后处理。
 *
 * <p>只作用于**返回集的后处理 / 装配**，不改变任何排序算法。能力：</p>
 * <ul>
 *   <li><b>去重</b>：内容重复 / 高度重叠的命中只保留首个（按输入顺序，即相关度优先）。
 *       判据为确定性纯文本规则（不调模型）：规范化空白后互为子串（较短者不短于
 *       {@link #SUBSTRING_MIN_CHARS}），或字符 n-gram Jaccard ≥ 阈值；</li>
 *   <li><b>相邻合并</b>：同一 {@code docId} 且 {@code chunkIndex} 相邻（差 ≤ 窗口）的命中合并为一个片段，
 *       正文按 {@code chunkIndex} 升序拼接、{@code score} 取成员最大值、并记录切片区间；</li>
 *   <li><b>字符上限</b>：合并 / 去重后片段正文总长受上限约束，超限时**按相关度优先保留**（输入即相关度序），
 *       保证不会因合并把高相关片段挤掉。上限由「绝对字符上限」{@code assembleMaxChars} 与
 *       「Token 预算」{@code assembleMaxTokens} 折算出的字符上限取**较小者**（P0-④：可配 Token 预算）。</li>
 * </ul>
 *
 * <p>边界：空集 / 单条 / 无相邻 / 空正文均稳健；始终返回新列表，不修改入参对象。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public final class AiRetrievalContextAssembler {

    /**
     * 触发「互为子串」去重的较短正文最小字符数：低于此长度不做子串判重，避免极短片段误杀。
     */
    private static final int SUBSTRING_MIN_CHARS = 8;

    /**
     * 字符 n-gram 的 n（中文场景按字符二元组更稳）。
     */
    private static final int NGRAM_SIZE = 2;

    /**
     * 未配置时的字符上限兜底值（与现状一致）。
     */
    private static final int DEFAULT_MAX_CHARS = 6000;

    private AiRetrievalContextAssembler() {
    }

    /**
     * 装配命中列表。
     *
     * @param hits    检索命中（已按相关度降序，保持输入相对顺序）
     * @param options 装配参数；为 {@code null} 时取默认配置
     * @return 装配后的片段列表（保持输入相关度顺序）
     */
    public static List<AiRetrievalHitVo> assemble(List<AiRetrievalHitVo> hits, Options options) {
        Options opts = options == null ? Options.defaults() : options;
        List<AiRetrievalHitVo> current = new ArrayList<>();
        if (hits != null) {
            for (AiRetrievalHitVo hit : hits) {
                if (hit != null) {
                    current.add(hit);
                }
            }
        }
        if (current.isEmpty()) {
            return List.of();
        }
        if (opts.dedupEnabled()) {
            current = dedup(current, opts.dedupJaccard());
        }
        if (opts.mergeAdjacentEnabled()) {
            current = mergeAdjacent(current, opts.mergeWindow());
        }
        int effectiveCap = effectiveMaxChars(opts);
        List<AiRetrievalHitVo> result = capByChars(current, effectiveCap);
        logSummary(opts, hits, result, effectiveCap);
        return result;
    }

    /**
     * 计算生效的**字符上限**：取「绝对字符上限」与「Token 预算折算出的字符上限」的较小者（P0-④）。
     *
     * <p>两者均未配置（≤0）时回退默认值 {@link #DEFAULT_MAX_CHARS}。用于装配截断，
     * 也供工具侧渲染时做同口径的安全截断。</p>
     *
     * @param options 装配参数；为 {@code null} 时取默认配置
     * @return 生效字符上限（正数）
     */
    public static int effectiveMaxChars(Options options) {
        Options opts = options == null ? Options.defaults() : options;
        int charCap = opts.maxChars() > 0 ? opts.maxChars() : DEFAULT_MAX_CHARS;
        int tokenDerived = AiTokenEstimator.tokenBudgetToCharCap(opts.maxTokens());
        if (tokenDerived <= 0) {
            return charCap;
        }
        return Math.min(charCap, tokenDerived);
    }

    /**
     * 输出装配的可观测量（P0-④）：生效字符上限、Token 预算、装配前后的片段数与估算 Token 数。
     *
     * @param opts         装配参数
     * @param input        装配前命中
     * @param output       装配后片段
     * @param effectiveCap 生效字符上限
     */
    private static void logSummary(Options opts, List<AiRetrievalHitVo> input, List<AiRetrievalHitVo> output,
                                   int effectiveCap) {
        int beforeChars = totalChars(input);
        int afterChars = totalChars(output);
        int beforeTokens = AiTokenEstimator.estimateTokens(joinContents(input));
        int afterTokens = AiTokenEstimator.estimateTokens(joinContents(output));
        log.info("检索上下文装配｜生效字符上限={}（绝对={}，Token预算={}）｜片段 {}→{}｜字符 {}→{}｜估算Token {}→{}",
            effectiveCap, opts.maxChars(), opts.maxTokens(),
            input == null ? 0 : input.size(), output.size(), beforeChars, afterChars, beforeTokens, afterTokens);
    }

    /**
     * 统计片段正文总字符数。
     *
     * @param hits 片段列表
     * @return 总字符数
     */
    private static int totalChars(List<AiRetrievalHitVo> hits) {
        int total = 0;
        if (hits != null) {
            for (AiRetrievalHitVo hit : hits) {
                total += hit.getContent() == null ? 0 : hit.getContent().length();
            }
        }
        return total;
    }

    /**
     * 拼接片段正文（用于估算 Token）。
     *
     * @param hits 片段列表
     * @return 以换行拼接的正文；无内容时为空串
     */
    private static String joinContents(List<AiRetrievalHitVo> hits) {
        StringBuilder sb = new StringBuilder();
        if (hits != null) {
            for (AiRetrievalHitVo hit : hits) {
                if (hit.getContent() != null) {
                    sb.append(hit.getContent()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * 内容去重：按输入顺序遍历，与已保留片段高度重合者丢弃。
     *
     * @param hits          命中列表
     * @param jaccardCutoff 字符 n-gram Jaccard 阈值
     * @return 去重后的片段列表
     */
    private static List<AiRetrievalHitVo> dedup(List<AiRetrievalHitVo> hits, double jaccardCutoff) {
        List<AiRetrievalHitVo> kept = new ArrayList<>();
        List<String> keptNorm = new ArrayList<>();
        List<Set<String>> keptGrams = new ArrayList<>();
        for (AiRetrievalHitVo hit : hits) {
            String norm = normalize(hit.getContent());
            boolean duplicate = false;
            for (int i = 0; i < kept.size(); i++) {
                if (isDuplicate(norm, keptNorm.get(i), keptGrams.get(i), jaccardCutoff)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                kept.add(hit);
                keptNorm.add(norm);
                keptGrams.add(grams(norm));
            }
        }
        return kept;
    }

    /**
     * 相邻切片合并：同一文档且切片号相邻者合并为一个片段。
     *
     * @param hits   命中列表（去重后）
     * @param window 合并窗口（切片号差值上限）
     * @return 合并后的片段列表
     */
    private static List<AiRetrievalHitVo> mergeAdjacent(List<AiRetrievalHitVo> hits, int window) {
        int effectiveWindow = Math.max(0, window);
        List<Fragment> groups = new ArrayList<>();
        for (AiRetrievalHitVo hit : hits) {
            Long docId = hit.getDocId();
            Integer idx = hit.getChunkIndex();
            Fragment target = null;
            if (docId != null && idx != null) {
                for (Fragment group : groups) {
                    if (group.docId != null && group.docId.equals(docId)
                        && idx >= group.minIndex - effectiveWindow && idx <= group.maxIndex + effectiveWindow) {
                        target = group;
                        break;
                    }
                }
            }
            if (target == null) {
                target = new Fragment();
                target.docId = docId;
                target.docName = hit.getDocName();
                target.minIndex = idx == null ? Integer.MAX_VALUE : idx;
                target.maxIndex = idx == null ? Integer.MIN_VALUE : idx;
                groups.add(target);
            } else {
                if (idx < target.minIndex) {
                    target.minIndex = idx;
                }
                if (idx > target.maxIndex) {
                    target.maxIndex = idx;
                }
            }
            target.members.add(hit);
        }

        List<AiRetrievalHitVo> result = new ArrayList<>(groups.size());
        for (Fragment group : groups) {
            if (group.members.size() == 1) {
                result.add(copy(group.members.get(0)));
                continue;
            }
            List<AiRetrievalHitVo> members = new ArrayList<>(group.members);
            members.sort(Comparator.comparingInt(hit -> hit.getChunkIndex() == null ? Integer.MAX_VALUE : hit.getChunkIndex()));
            StringBuilder content = new StringBuilder();
            Double maxScore = null;
            for (AiRetrievalHitVo member : members) {
                String part = member.getContent() == null ? "" : member.getContent().trim();
                if (part.isEmpty()) {
                    continue;
                }
                if (content.length() > 0) {
                    content.append("\n");
                }
                content.append(part);
                if (member.getScore() != null && (maxScore == null || member.getScore() > maxScore)) {
                    maxScore = member.getScore();
                }
            }
            AiRetrievalHitVo merged = new AiRetrievalHitVo();
            merged.setDocId(group.docId);
            merged.setDocName(group.docName);
            merged.setChunkIndex(group.minIndex == Integer.MAX_VALUE ? null : group.minIndex);
            merged.setChunkIndexEnd(group.maxIndex == Integer.MIN_VALUE ? null : group.maxIndex);
            merged.setScore(maxScore);
            merged.setContent(content.toString());
            result.add(merged);
        }
        return result;
    }

    /**
     * 按字符上限保留片段：输入即相关度序，故从前向后累计、超出即停，保证高相关片段优先。
     *
     * @param fragments 片段列表
     * @param maxChars  正文总字符上限
     * @return 受上限约束后的片段列表
     */
    private static List<AiRetrievalHitVo> capByChars(List<AiRetrievalHitVo> fragments, int maxChars) {
        int cap = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        List<AiRetrievalHitVo> result = new ArrayList<>();
        int used = 0;
        for (AiRetrievalHitVo fragment : fragments) {
            int length = fragment.getContent() == null ? 0 : fragment.getContent().length();
            if (result.isEmpty()) {
                // 首条（最高相关）无论如何保留；自身超限时仅截断其正文
                if (length > cap) {
                    AiRetrievalHitVo truncated = copy(fragment);
                    truncated.setContent(fragment.getContent().substring(0, cap));
                    result.add(truncated);
                } else {
                    result.add(fragment);
                }
                used += Math.min(length, cap);
                continue;
            }
            if (used + length > cap) {
                break;
            }
            result.add(fragment);
            used += length;
        }
        return result;
    }

    /**
     * 判断两条规范化正文是否为重复（互为子串或 n-gram Jaccard 达阈值）。
     *
     * @param a          规范化正文 A
     * @param b          规范化正文 B
     * @param bGrams     B 的字符 n-gram 集合（预计算）
     * @param jaccardCutoff Jaccard 阈值
     * @return 是否视为重复
     */
    private static boolean isDuplicate(String a, String b, Set<String> bGrams, double jaccardCutoff) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b);
        }
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() >= SUBSTRING_MIN_CHARS && longer.contains(shorter)) {
            return true;
        }
        Set<String> aGrams = grams(a);
        if (aGrams.isEmpty() || bGrams.isEmpty()) {
            return false;
        }
        int intersection = 0;
        for (String gram : aGrams) {
            if (bGrams.contains(gram)) {
                intersection++;
            }
        }
        int union = aGrams.size() + bGrams.size() - intersection;
        return union > 0 && (double) intersection / union >= jaccardCutoff;
    }

    /**
     * 规范化空白：连续空白折叠为单空格并去除首尾。
     *
     * @param content 原文
     * @return 规范化结果；空值返回空串
     */
    private static String normalize(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").trim();
    }

    /**
     * 计算字符 n-gram 集合；正文短于 n 时退化为单元素集合（非空）。
     *
     * @param text 规范化正文
     * @return n-gram 集合
     */
    private static Set<String> grams(String text) {
        Set<String> result = new HashSet<>();
        if (text.isEmpty()) {
            return result;
        }
        if (text.length() < NGRAM_SIZE) {
            result.add(text);
            return result;
        }
        for (int i = 0; i + NGRAM_SIZE <= text.length(); i++) {
            result.add(text.substring(i, i + NGRAM_SIZE));
        }
        return result;
    }

    /**
     * 浅拷贝命中对象（避免后续截断修改入参）。
     *
     * @param source 源对象
     * @return 新对象
     */
    private static AiRetrievalHitVo copy(AiRetrievalHitVo source) {
        AiRetrievalHitVo target = new AiRetrievalHitVo();
        target.setDocId(source.getDocId());
        target.setDocName(source.getDocName());
        target.setChunkIndex(source.getChunkIndex());
        target.setChunkIndexEnd(source.getChunkIndexEnd());
        target.setScore(source.getScore());
        target.setContent(source.getContent());
        return target;
    }

    /**
     * 合并过程中的片段分组（内部可变状态）。
     */
    private static final class Fragment {

        /**
         * 组内文档主键
         */
        private Long docId;

        /**
         * 组内文档名（取首个成员）
         */
        private String docName;

        /**
         * 组内最小切片号（无切片号为 {@link Integer#MAX_VALUE}）
         */
        private int minIndex;

        /**
         * 组内最大切片号（无切片号为 {@link Integer#MIN_VALUE}）
         */
        private int maxIndex;

        /**
         * 组内成员（按加入顺序，即相关度序）
         */
        private final List<AiRetrievalHitVo> members = new ArrayList<>();
    }

    /**
     * 装配参数（不可变）。
     *
     * @param dedupEnabled          是否启用内容去重
     * @param mergeAdjacentEnabled  是否启用相邻切片合并
     * @param mergeWindow           合并窗口（切片号差值上限）
     * @param dedupJaccard          内容去重的 n-gram Jaccard 阈值
     * @param maxChars              装配后正文总字符上限（绝对上限）
     * @param maxTokens             装配后正文 Token 预算（P0-④，折算为字符上限后与 maxChars 取较小者）
     */
    public record Options(boolean dedupEnabled, boolean mergeAdjacentEnabled, int mergeWindow,
                          double dedupJaccard, int maxChars, int maxTokens) {

        /**
         * 取默认配置（与 {@link AiProperties.Rag} 默认值一致）。
         *
         * @return 默认装配参数
         */
        public static Options defaults() {
            return from(new AiProperties.Rag());
        }

        /**
         * 从平台配置构造装配参数。
         *
         * @param rag RAG 配置
         * @return 装配参数
         */
        public static Options from(AiProperties.Rag rag) {
            return new Options(rag.isAssembleDedupEnabled(), rag.isAssembleMergeAdjacentEnabled(),
                rag.getAssembleMergeWindow(), rag.getAssembleDedupJaccard(), rag.getAssembleMaxChars(),
                rag.getAssembleMaxTokens());
        }
    }

}