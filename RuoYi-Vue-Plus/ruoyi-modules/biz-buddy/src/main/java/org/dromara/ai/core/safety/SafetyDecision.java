package org.dromara.ai.core.safety;

import java.util.List;

/**
 * 安全合规的统一处置结果（由 {@link AiSafetyGuard} 产出，供各出口消费）。
 *
 * @param action 有效动作：{@link SafetyAction#BLOCK} 表示须阻断；文本发生变化时为 {@link SafetyAction#MASK}；
 *               否则为 {@link SafetyAction#LOG}
 * @param text   处置后的文本（{@code action=BLOCK} 时为 {@code null}）
 * @param hit    是否命中（内容命中或 PII 命中）
 * @param hits   命中项（命中词 / PII 类型；未命中为空列表）
 * @author BizBuddy
 */
public record SafetyDecision(SafetyAction action, String text, boolean hit, List<String> hits) {

    /**
     * 未命中：原样放行。
     *
     * @param text 原文
     * @return 放行结果
     */
    public static SafetyDecision pass(String text) {
        return new SafetyDecision(SafetyAction.LOG, text, false, List.of());
    }

    /**
     * 阻断。
     *
     * @param hits 命中项
     * @return 阻断结果
     */
    public static SafetyDecision block(List<String> hits) {
        return new SafetyDecision(SafetyAction.BLOCK, null, true, hits);
    }

    /**
     * @return 是否须阻断
     */
    public boolean blocked() {
        return action == SafetyAction.BLOCK;
    }

}