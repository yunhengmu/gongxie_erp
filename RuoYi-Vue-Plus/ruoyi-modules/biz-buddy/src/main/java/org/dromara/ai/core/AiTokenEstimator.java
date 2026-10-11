package org.dromara.ai.core;

/**
 * 轻量 Token 估算器（纯函数，无状态、无外部依赖）。
 *
 * <p>用于给「检索上下文装配」（P0-④）提供**可配的 Token 预算**口径：不引入分词器 / 模型，
 * 仅按字符类别做确定性折算，便于在装配处做预算约束、在评测与排障时输出可解释的估算值。</p>
 *
 * <p>估算口径（与评测脚本 {@code e2e/eval/eval-rag.mjs} 保持一致）：</p>
 * <ul>
 *   <li><b>CJK 字符</b>（中日韩统一表意文字及其扩展、兼容区，CJK 标点，全角字符）≈ <b>1 token / 字符</b>；</li>
 *   <li><b>其它字符</b>（拉丁字母、数字、空白、标点等）≈ <b>4 字符 / token</b>，按 1/4 折算并**向上取整**。</li>
 * </ul>
 *
 * <p>说明：本估算是**近似**值，并非某具体模型分词器的精确结果，仅用于预算约束与报告；
 * 与「字数」分开统计，避免把 token 与字符混为一谈。</p>
 *
 * @author BizBuddy
 */
public final class AiTokenEstimator {

    /**
     * 非 CJK 字符的折算比：约 4 个字符折合 1 个 token。
     */
    private static final double OTHER_CHARS_PER_TOKEN = 4.0;

    /**
     * CJK 保守折算：1 token ≈ 1 字符（用于把 Token 预算折算为字符上限的绝对兜底）。
     */
    private static final int CJK_CHARS_PER_TOKEN = 1;

    private AiTokenEstimator() {
    }

    /**
     * 估算文本的 Token 数。
     *
     * <p>CJK 字符按 1 token / 字符计；其它字符按 4 字符 / token 折算并向上取整。
     * 空串 / {@code null} 返回 0。</p>
     *
     * @param text 待估算文本
     * @return 估算 Token 数（非负）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        int length = text.length();
        for (int i = 0; i < length; ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        int otherTokens = (int) Math.ceil(other / OTHER_CHARS_PER_TOKEN);
        return cjk + otherTokens;
    }

    /**
     * 把 Token 预算折算为**字符上限**（保守口径）。
     *
     * <p>采用 CJK 1 token / 字符的保守折算，即返回与预算相同的字符数：这样对 CJK 内容
     * 「字符数 ≤ token 预算」恒成立（不会因折算放宽而突破预算），对非 CJK 内容则由
     * 绝对字符上限（{@code assembleMaxChars}）兜底。默认预算取 6000 时恰好等效于「6000 字」。</p>
     *
     * @param tokenBudget Token 预算；非正数表示未设置，返回 {@code 0}
     * @return 折算出的字符上限（非负）
     */
    public static int tokenBudgetToCharCap(int tokenBudget) {
        if (tokenBudget <= 0) {
            return 0;
        }
        return tokenBudget * CJK_CHARS_PER_TOKEN;
    }

    /**
     * 判断码点是否属于 CJK 字符（统一表意文字及扩展 / 兼容区、CJK 标点、全角字符）。
     *
     * @param codePoint Unicode 码点
     * @return 是否 CJK
     */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
            || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
            || (codePoint >= 0x3000 && codePoint <= 0x303F)
            || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }

}