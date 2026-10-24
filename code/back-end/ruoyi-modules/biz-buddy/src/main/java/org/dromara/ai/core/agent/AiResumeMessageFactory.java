package org.dromara.ai.core.agent;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import org.dromara.ai.domain.AiPermissionDecisionLog;
import org.dromara.common.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * HITL 恢复消息构造（无状态纯函数）。
 *
 * <p>把用户对工具调用的处置结果（确认 / 拒绝 / 记住 / 拒绝原因）翻译为 AgentScope 的
 * {@code ConfirmResult} 列表与恢复提示语，随一条 user message 回传框架以驱动续跑。</p>
 *
 * <p>无任何依赖，故与 {@link AiSseSupport} / {@link AiPermissionSupport} 同口径：
 * 包级可见 + 静态方法，不注册 Bean。</p>
 *
 * @author BizBuddy
 */
final class AiResumeMessageFactory {

    private AiResumeMessageFactory() {
    }

    /**
     * 构造恢复用消息（携带用户对工具调用的处置结果）。
     *
     * @param pending 待确认状态
     * @return 恢复消息列表
     */
    static List<Msg> build(AiConfirmPendingStore.PendingConfirm pending) {
        Map<String, AiConfirmPendingStore.PendingToolCall> toolCalls = new LinkedHashMap<>();
        for (AiConfirmPendingStore.PendingToolCall call : pending.toolCalls()) {
            toolCalls.put(call.toolCallId(), call);
        }
        List<ConfirmResult> results = new ArrayList<>();
        boolean rejectedAll = true;
        LinkedHashSet<String> rejectReasons = new LinkedHashSet<>();
        for (AiConfirmPendingStore.PendingDecision decision : pending.decisions()) {
            AiConfirmPendingStore.PendingToolCall call = toolCalls.get(decision.toolCallId());
            if (call == null) {
                continue;
            }
            // 必须带回 content 与 ASKING 状态：框架恢复执行时按 ToolUseBlock.getContent() 校验入参，
            // 缺失会报 argument "content" is null（详见 docs/00 第 29 项）
            ToolUseBlock block = new ToolUseBlock(call.toolCallId(), call.toolName(), call.input(),
                call.content(), null, ToolCallState.ASKING);
            List<PermissionRule> rules = decision.confirmed() && decision.remember()
                ? List.of(new PermissionRule(call.toolName(), null, PermissionBehavior.ALLOW,
                    AiPermissionDecisionLog.SOURCE_REMEMBER))
                : null;
            // 自定义拒绝原因随 ConfirmResult 回传（拒绝时框架在工具结果事件中带上该说明）
            String reason = decision.confirmed() ? null : decision.reason();
            results.add(new ConfirmResult(decision.confirmed(), block, rules, reason));
            if (decision.confirmed()) {
                rejectedAll = false;
            } else if (StringUtils.isNotBlank(reason)) {
                rejectReasons.add(reason);
            }
        }
        String text;
        if (rejectedAll) {
            String reasonText = rejectReasons.isEmpty() ? "" : "，原因是：" + String.join("；", rejectReasons);
            text = "用户拒绝了上述工具调用" + reasonText
                + "，请不要重复请求执行，改为基于已有信息作答或明确说明无法完成。";
        } else {
            text = "用户已确认执行上述工具调用，请继续。";
        }
        Msg msg = Msg.builder()
            .role(MsgRole.USER)
            .textContent(text)
            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
            .build();
        return List.of(msg);
    }
}
