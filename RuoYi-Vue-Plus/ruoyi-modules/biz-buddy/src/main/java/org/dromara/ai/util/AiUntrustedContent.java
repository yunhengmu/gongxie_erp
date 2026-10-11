package org.dromara.ai.util;

/**
 * 外部内容（不可信数据）的边界标记工具（提示词注入防护）。
 *
 * <p>把「来自外部的文本」（用户原话、知识库片段、工具结果、附件解析文本）用 {@code <untrusted>}
 * 包裹，配合 {@code PromptHardeningMiddleware} 在 system prompt 中的声明，让模型把包裹内容当作
 * **数据**而非**指令**。</p>
 *
 * @author BizBuddy
 */
public final class AiUntrustedContent {

    /**
     * 边界开标签
     */
    private static final String OPEN = "<untrusted>";

    /**
     * 边界闭标签
     */
    private static final String CLOSE = "</untrusted>";

    private AiUntrustedContent() {
    }

    /**
     * 包裹外部内容（默认启用）。
     *
     * @param text 外部文本（可空）
     * @return 包裹后的文本；入参为空时原样返回
     */
    public static String wrap(String text) {
        return wrap(text, true);
    }

    /**
     * 包裹外部内容（按开关）。
     *
     * @param text    外部文本（可空）
     * @param enabled 是否启用包裹（false 时原样返回）
     * @return 包裹后的文本
     */
    public static String wrap(String text, boolean enabled) {
        if (text == null || text.isEmpty() || !enabled) {
            return text;
        }
        return OPEN + "\n" + text + "\n" + CLOSE;
    }

}