package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 候选专家视图对象（REQ-AI-30）。
 *
 * <p>由入口智能体每轮**现算**的候选清单（启用 ∩ 按提问用户可见 ∩ {@code agent_role='2'}）。
 * 管理面用于核对「本轮到底有哪些专家可选」，编排内部直接走服务层、不经接口。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiExpertVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 专家智能体主键
     */
    private Long agentId;

    /**
     * 专家标识（ai_agent.agent_code，即路由决策里的 expert_agent_key）
     */
    private String agentKey;

    /**
     * 专家名称
     */
    private String agentName;

    /**
     * 能力描述（注入入口智能体的路由提示词；为空回落 description）
     */
    private String capabilityDesc;

    /**
     * 是否自动路由（1 进入口智能体的自动候选清单；0 只能被用户手动指定；REQ-AI-33）
     *
     * <p>{@code /experts/candidates} 只会返回 {@code '1'}；{@code /experts} 两者皆有可能，
     * 供工作台在「指定专家」下拉里对仅手动指定的专家做标注。</p>
     */
    private String autoRoute;

}
