package org.dromara.ai.core.agent;

import org.dromara.common.core.utils.StringUtils;

/**
 * 候选专家（REQ-AI-30）。
 *
 * <p>「每轮现算」的候选清单元素：启用 ∩ 按提问用户可见 ∩ {@code agent_role='2'}。
 * 清单注入入口智能体的路由提示词，同时作为服务端校验 {@code expert_agent_key} 的白名单
 * （模型只能从清单里选，清单里没有的一律非法）。</p>
 *
 * <p><b>为什么不进装配指纹</b>：入口智能体的装配实例是跨用户共享单例，而清单随用户与配置变化；
 * 清单属于运行期上下文，每轮注入即可，与 {@code CurrentTimeMiddleware} 的每轮现算同一思路。</p>
 *
 * @param agentId       专家智能体主键
 * @param agentKey      专家标识（{@code ai_agent.agent_code}，模型提交的 {@code expert_agent_key}）
 * @param agentName     专家名称
 * @param expertDomain  领域标签（**2026-10-02 起已停用**：定义页移除该字段、换专家阶梯取消；列保留、不参与提示词渲染与筛选）
 * @param capabilityDesc 能力描述（注入路由提示词；为空回落 description）
 * @author BizBuddy
 */
public record AiExpertCandidate(Long agentId, String agentKey, String agentName, String expertDomain,
                                String capabilityDesc) {

    /**
     * 构造候选（能力描述为空时回落 description）。
     *
     * @param agentId     专家主键
     * @param agentKey    专家标识
     * @param agentName   专家名称
     * @param domain      领域标签
     * @param capability  能力描述
     * @param description 一句话职责说明（兜底）
     * @return 候选专家
     */
    static AiExpertCandidate of(Long agentId, String agentKey, String agentName, String domain,
                                String capability, String description) {
        String capabilityDesc = StringUtils.isBlank(capability) ? description : capability;
        return new AiExpertCandidate(agentId, agentKey, agentName, domain, capabilityDesc);
    }

    /**
     * 渲染为路由提示词中的一行。
     *
     * <p>2026-10-02 起**不再输出「领域」**；2026-10-03 专家直答修订后**不再输出「默认契约字段」**
     * （已无输出契约），小Z 仅按「名称 + 擅长（能力描述，为空回落一句话职责说明）」判断该找谁。</p>
     *
     * @return 形如 {@code - key=finance_agent | 名称=财务专家 | 擅长=…} 的文本
     */
    String render() {
        return "- key=" + agentKey
            + " | 名称=" + agentName
            + " | 擅长=" + (StringUtils.isBlank(capabilityDesc) ? "（未填写）" : capabilityDesc);
    }
}
