package org.dromara.ai.core.route;

import java.util.List;

/**
 * 路由判断请求（docs/21 §5.1 / §5.2）。
 *
 * <p>{@code state} 是决策模型的全部输入，须<b>只放判断必需的事实</b>；且按口径 P9，
 * 外呼前已强制过 PII 脱敏（{@link org.dromara.ai.util.AiPiiMasker}），故此处的 {@code state}
 * 已是「脱敏后的外呼文本」。</p>
 *
 * @param state      已脱敏的外呼文本（本轮用户原话 + 候选清单）
 * @param candidates 候选专家清单（内嵌 {@link Candidate}）
 * @author BizBuddy
 */
public record RouteJudgeRequest(String state, List<Candidate> candidates) {

    /**
     * 候选专家（用于构造决策模型的闭集判据）。
     *
     * @param agentCode   专家标识（{@code ai_agent.agent_code}，与 {@code expert_agent_key} 口径一致）
     * @param name        专家名称
     * @param description 判据文本（专家的「擅长 / 描述」）
     */
    public record Candidate(String agentCode, String name, String description) {
    }

}