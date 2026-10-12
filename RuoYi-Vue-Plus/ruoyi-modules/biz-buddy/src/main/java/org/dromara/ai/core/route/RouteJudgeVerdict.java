package org.dromara.ai.core.route;

import java.util.Map;

/**
 * 路由判断结果（docs/21 §5.1）。
 *
 * <p>口径：{@code outputTokens} 恒为 0（百炼只回 input_tokens），故费用口径只用
 * {@link #inputTokens()}，<b>不引入输出 token</b>参与任何计算。</p>
 *
 * @param choice        决策模型选择的选项（{@code agent_code} / {@code self} / {@code other}）
 * @param confidence    置信度（choice 类别才有；noul 类别不带，可为 null）
 * @param probabilities 概率分布（可为空；影子校准用）
 * @param latencyMs     本次判定耗时（毫秒，本地计量）
 * @param inputTokens   本次判定的输入 Token（费用口径）
 * @param error         非空表示本次判定不可用（超时 / 报错 / 解析失败 / 越界）
 * @author BizBuddy
 */
public record RouteJudgeVerdict(String choice, Double confidence, Map<String, Double> probabilities,
                                long latencyMs, long inputTokens, String error) {
}