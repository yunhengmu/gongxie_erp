package org.dromara.ai.core.agent;

/**
 * 平台写入 {@code RuntimeContext} 的额外键。
 *
 * <p>框架的 {@code RuntimeContext} 只带 userId / sessionId 等基础字段，业务侧需要随本轮对话
 * 传递的信息（如用户本轮原话）通过 {@code put(key, value)} 挂上去，工具侧再按键读取。</p>
 *
 * @author BizBuddy
 */
public final class AiRuntimeKeys {

    /**
     * 用户本轮消息正文（过审后的口径）：显式记忆工具据此校验「证据确实来自用户原话」
     */
    public static final String CURRENT_USER_MESSAGE = "ai.chat.currentUserMessage";

    private AiRuntimeKeys() {
    }
}
