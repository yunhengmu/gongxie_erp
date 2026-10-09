package org.dromara.ai.core.agent;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import org.dromara.ai.domain.AiPermissionDecisionLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiResumeMessageFactory} 的纯单元测试（不碰运行时 / 数据库 / Redis）。
 *
 * <p>覆盖：全拒绝文案与拒绝原因回传、确认文案、remember 规则生成、无效决策跳过、
 * 以及 {@code ToolUseBlock} 必须带 {@code content} 与 {@code ASKING} 状态（缺失会致框架恢复失败）。</p>
 */
@DisplayName("HITL 恢复消息构造")
class AiResumeMessageFactoryTest {

    @Test
    @DisplayName("全拒绝且无原因：使用默认拒绝文案")
    void shouldMarkRejectedAllWithoutReason() {
        List<Msg> messages = AiResumeMessageFactory.build(pending(
            List.of(call("c1", "create_notice", "{\"a\":1}")),
            List.of(decision("c1", false, false, null))));

        assertEquals(1, messages.size());
        String text = messages.get(0).getTextContent();
        assertTrue(text.startsWith("用户拒绝了上述工具调用"), text);
        assertFalse(text.contains("原因是"), text);
    }

    @Test
    @DisplayName("全拒绝带原因：原因去重后以「；」连接回传")
    void shouldIncludeDistinctRejectionReasons() {
        List<Msg> messages = AiResumeMessageFactory.build(pending(
            List.of(call("c1", "create_notice", "{}"), call("c2", "delete_user", "{}"),
                call("c3", "update_user", "{}")),
            List.of(decision("c1", false, false, "数据敏感"),
                decision("c2", false, false, "数据敏感"),
                decision("c3", false, false, "无权限"))));

        String text = messages.get(0).getTextContent();
        assertTrue(text.contains("，原因是：数据敏感；无权限"), text);
    }

    @Test
    @DisplayName("存在确认项：使用确认文案且 rejectedAll 为 false")
    void shouldMarkConfirmed() {
        List<Msg> messages = AiResumeMessageFactory.build(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("c1", true, false, null))));

        assertEquals("用户已确认执行上述工具调用，请继续。", messages.get(0).getTextContent());
    }

    @Test
    @DisplayName("仅「确认且记住」生成 allow 规则；其余为 null")
    void shouldAttachRulesOnlyWhenConfirmedAndRemember() {
        ConfirmResult remember = results(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("c1", true, true, null)))).get(0);
        assertNotNull(remember.getRules());
        assertEquals(PermissionBehavior.ALLOW, remember.getRules().get(0).behavior());
        assertEquals(AiPermissionDecisionLog.SOURCE_REMEMBER, remember.getRules().get(0).source());

        ConfirmResult once = results(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("c1", true, false, null)))).get(0);
        assertNull(once.getRules());

        ConfirmResult rejected = results(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("c1", false, true, "不行")))).get(0);
        assertNull(rejected.getRules());
    }

    @Test
    @DisplayName("拒绝原因：仅拒绝项非空，确认项为 null")
    void shouldCarryReasonOnlyWhenRejected() {
        ConfirmResult rejected = results(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("c1", false, false, "数据敏感")))).get(0);
        assertEquals("数据敏感", rejected.getReason());

        ConfirmResult confirmed = results(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("c1", true, false, "忽略")))).get(0);
        assertNull(confirmed.getReason());
    }

    @Test
    @DisplayName("决策找不到对应工具调用：跳过，结果为空且仍判全拒绝")
    void shouldSkipDecisionWithoutMatchingToolCall() {
        List<Msg> messages = AiResumeMessageFactory.build(pending(
            List.of(call("c1", "create_notice", "{}")),
            List.of(decision("cX", true, false, null))));

        assertEquals(0, results(messages).size());
        assertTrue(messages.get(0).getTextContent().contains("用户拒绝了上述工具调用"));
    }

    @Test
    @DisplayName("工具块必须带回 content 与 ASKING 状态，元数据挂确认结果")
    void shouldCarryContentAskingStateAndMetadata() {
        List<Msg> messages = AiResumeMessageFactory.build(pending(
            List.of(call("c1", "create_notice", "{\"a\":1}")),
            List.of(decision("c1", true, false, null))));

        Msg msg = messages.get(0);
        assertNotNull(msg.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS));

        ToolUseBlock block = (ToolUseBlock) results(messages).get(0).getToolCall();
        assertEquals("c1", block.getId());
        assertEquals("create_notice", block.getName());
        assertEquals("{\"a\":1}", block.getContent());
        assertEquals(ToolCallState.ASKING, block.getState());
    }

    // ---------------- fixtures ----------------

    private static AiConfirmPendingStore.PendingToolCall call(String id, String name, String content) {
        return new AiConfirmPendingStore.PendingToolCall(id, name, Map.of("a", 1), content);
    }

    private static AiConfirmPendingStore.PendingDecision decision(String id, boolean confirmed,
                                                                 boolean remember, String reason) {
        return new AiConfirmPendingStore.PendingDecision(id, confirmed, remember, reason);
    }

    private static AiConfirmPendingStore.PendingConfirm pending(
        List<AiConfirmPendingStore.PendingToolCall> calls,
        List<AiConfirmPendingStore.PendingDecision> decisions) {
        return new AiConfirmPendingStore.PendingConfirm(1L, 2L, 3L, 4L, "reply-1",
            calls, decisions, "2026-01-01T00:00:00", null);
    }

    @SuppressWarnings("unchecked")
    private static List<ConfirmResult> results(List<Msg> messages) {
        return (List<ConfirmResult>) messages.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
    }

    private static List<ConfirmResult> results(AiConfirmPendingStore.PendingConfirm pending) {
        return results(AiResumeMessageFactory.build(pending));
    }
}
