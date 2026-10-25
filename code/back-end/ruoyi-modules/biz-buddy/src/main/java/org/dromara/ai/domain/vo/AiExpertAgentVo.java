package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.ai.domain.AiExpertAgent;

import java.io.Serial;

/**
 * 专家智能体视图对象 ai_expert_agent（REQ-AI-33）。
 *
 * <p>承载公共字段 + 专家专属字段；{@code autoRoute} 用于专家配置页展示
 * 「是否自动路由」（0 只能被用户手动指定）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = AiExpertAgent.class)
public class AiExpertAgentVo extends AiAgentVo {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 能力描述（注入入口智能体的路由提示词；为空回落 {@code description}）
     */
    private String capabilityDesc;

    /**
     * 专家是否允许使用写工具（定义页无此开关，恒为 '1'）
     */
    private String expertWriteEnabled;

    /**
     * 是否自动路由（1 进入口智能体的自动候选清单；0 只能被用户手动指定）
     */
    private String autoRoute;

}
