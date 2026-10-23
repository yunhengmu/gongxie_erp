package org.dromara.ai.core.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.core.agent.AiRuntimeKeys;
import org.dromara.common.core.utils.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 显式记忆工具的证据校验。
 *
 * <p>显式记忆会长期影响后续对话，因此坚持两条硬约束：① 证据必须是**用户本轮消息中的原话片段**
 * （防止模型凭推测写入、也防止外部内容经提示注入改写记忆）；② 用户本轮消息必须**明确表达**
 * 记住（或忘掉）的意图。任一不满足即拒绝，由模型改用普通回答。</p>
 *
 * @author BizBuddy
 */
final class DeclaredMemoryGuard {

    /**
     * 「记住」意图关键词
     */
    private static final List<String> REMEMBER_INTENTS = List.of(
        "记住", "牢记", "记得", "别忘了", "不要忘", "记一下", "记录", "以后", "今后", "往后",
        "我习惯", "我喜欢", "我偏好", "我讨厌");

    /**
     * 「忘掉 / 撤销」意图关键词
     */
    private static final List<String> FORGET_INTENTS = List.of(
        "忘掉", "忘记", "别记", "不要记", "不用记", "删掉", "删除", "取消", "不用再",
        "以后不用", "以后不要", "以后别", "不再");

    /**
     * 证据最短长度（去空白后）
     */
    private static final int MIN_EVIDENCE_CHARS = 2;

    private DeclaredMemoryGuard() {
    }

    /**
     * 校验证据是否可作为本次显式记忆的依据。
     *
     * @param param    工具调用参数（取运行时上下文中的用户原话）
     * @param evidence 模型摘录的用户原话
     * @param forget   是否为「忘掉」类操作
     * @return 错误说明；通过校验时返回 null
     */
    static String validate(ToolCallParam param, String evidence, boolean forget) {
        RuntimeContext ctx = param == null ? null : param.getRuntimeContext();
        String message = ctx == null ? null : ctx.get(AiRuntimeKeys.CURRENT_USER_MESSAGE, String.class);
        if (StringUtils.isBlank(message)) {
            return "无法获取用户本轮消息，已拒绝改写长期记忆";
        }
        String quote = normalize(evidence);
        if (quote.length() < MIN_EVIDENCE_CHARS) {
            return "evidence 必须原样摘录用户本轮消息中的原话（至少 " + MIN_EVIDENCE_CHARS + " 个字）";
        }
        if (!normalize(message).contains(quote)) {
            return "evidence 不是用户本轮消息中的原话片段，已拒绝改写长期记忆";
        }
        if (!hasIntent(message, forget)) {
            return forget
                ? "用户本轮消息没有要求忘掉或撤销某条记忆的表述"
                : "用户本轮消息没有明确要求记住的表述";
        }
        return null;
    }

    /**
     * 判断用户消息是否含对应意图关键词。
     *
     * @param message 用户原话
     * @param forget  是否为「忘掉」类操作
     * @return 是否命中意图
     */
    private static boolean hasIntent(String message, boolean forget) {
        for (String word : forget ? FORGET_INTENTS : REMEMBER_INTENTS) {
            if (message.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化文本：去掉全部空白并转小写，便于中文原话的宽松比对。
     *
     * @param text 原始文本
     * @return 归一化文本（null 视为空串）
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
