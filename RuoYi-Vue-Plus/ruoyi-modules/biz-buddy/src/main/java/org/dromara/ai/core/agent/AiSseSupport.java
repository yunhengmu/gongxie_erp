package org.dromara.ai.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 对话 SSE 下发的无状态支撑（事件信封、下发、收尾与文本工具）。
 *
 * <p>这些方法与对话状态无关，从 {@link AiChatFacade} 抽出后便于复用与单测。全部方法不持有可变状态，
 * 可在 Reactor 订阅线程直接调用；{@code emitter} 为 null（无人值守链路）时统一下发动作全部丢弃。</p>
 *
 * @author BizBuddy
 */
@Slf4j
final class AiSseSupport {

    private AiSseSupport() {
    }

    /**
     * 构造只带 type 的事件信封。
     *
     * @param type 事件类型
     * @return 事件载荷
     */
    static Map<String, Object> envelope(String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        return payload;
    }

    /**
     * 下发一条 SSE 事件（统一使用无名消息 + JSON 信封）。
     *
     * @param emitter SSE 发射器；为 null 时（无人值守链路）直接丢弃
     * @param payload 事件载荷
     */
    static void send(SseEmitter emitter, Map<String, Object> payload) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(JsonUtils.toJsonString(payload)));
        } catch (Exception e) {
            log.debug("SSE 下发失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    /**
     * 关闭 SSE 流。
     *
     * @param emitter SSE 发射器；为 null 时（无人值守链路）不做处理
     */
    static void complete(SseEmitter emitter) {
        if (emitter != null) {
            emitter.complete();
        }
    }

    /**
     * 截断过长文本。
     *
     * @param text     原文
     * @param maxChars 最大字符数
     * @return 截断结果
     */
    static String abbreviate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...（已截断）";
    }

    /**
     * 提取根因错误信息，并归一化为**面向用户**的提示。
     *
     * <p>上游模型 / 传输层异常（如 DashScope 的 403 `AllocationQuota.FreeTierOnly`）原始报文很长、
     * 且夹杂英文 JSON，直接抛给用户既难懂又难看；各调用点已 {@code log.error} 落服务端日志，
     * 故这里只做「给人看」的映射与截断，原始报文仍可在日志中排障。</p>
     *
     * @param error 异常
     * @return 归一化后的错误说明
     */
    static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return friendlyModelError(StringUtils.isBlank(message) ? cause.getClass().getSimpleName() : message);
    }

    /**
     * 把上游模型 / 传输层错误归一化为面向用户的提示；无法识别时按长度截断原文。
     *
     * @param message 根因错误信息
     * @return 面向用户的提示
     */
    static String friendlyModelError(String message) {
        if (StringUtils.isBlank(message)) {
            return message;
        }
        String lower = message.toLowerCase();
        // 额度：DashScope 免费额度用尽 / 欠费
        if (lower.contains("allocationquota") || lower.contains("quota exhausted") || lower.contains("free quota")
            || lower.contains("insufficientquota") || lower.contains("arrearage") || lower.contains("free tier")) {
            return "模型额度不足或已用尽，请联系管理员在「模型配置」更换密钥，或到模型服务商充值 / 关闭「仅使用免费额度」。";
        }
        // 鉴权：密钥无效 / 未授权
        if (lower.contains("invalidapikey") || lower.contains("invalid_api_key") || lower.contains("unauthorized")
            || lower.contains("authentication")) {
            return "模型密钥无效或未授权，请在「模型配置」检查密钥。";
        }
        // 限流
        if (lower.contains("throttling") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return "模型请求过于频繁（限流），请稍后重试。";
        }
        // 模型侧内容审核
        if (lower.contains("data_inspection_failed") || lower.contains("datainspectionfailed")) {
            return "内容未通过模型服务商的安全审核，请调整提问内容后重试。";
        }
        // 传输异常
        if (lower.contains("transport error") || lower.contains("transport failed")) {
            return "模型服务调用失败（网络或服务端异常），请稍后重试；持续失败请联系管理员查看服务端日志。";
        }
        return abbreviate(message, 200);
    }
}
