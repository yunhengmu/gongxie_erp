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
     * 提取根因错误信息。
     *
     * @param error 异常
     * @return 错误说明
     */
    static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StringUtils.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }
}
