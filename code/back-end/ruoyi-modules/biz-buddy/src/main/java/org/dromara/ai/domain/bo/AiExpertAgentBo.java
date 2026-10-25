package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;

/**
 * 专家智能体业务对象 ai_expert_agent（REQ-AI-33）。
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = AiExpertAgent.class, reverseConvertGenerate = false)
public class AiExpertAgentBo extends AiAgentBo {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 能力描述（注入入口智能体的路由提示词；为空回落 description）
     */
    private String capabilityDesc;

    /**
     * 专家是否允许使用写工具（0 不允许 / 1 允许；定义页无此开关，服务端恒置 '1'）
     */
    @Pattern(regexp = "[01]", message = "专家写工具开关只能是 0 不允许 / 1 允许", groups = {AddGroup.class, EditGroup.class})
    private String expertWriteEnabled;

    /**
     * 是否自动路由（1 进入口智能体的自动候选清单 / 0 只能被用户手动指定；留空按开启处理）
     */
    @Pattern(regexp = "[01]", message = "自动路由开关只能是 0 关闭 / 1 开启", groups = {AddGroup.class, EditGroup.class})
    private String autoRoute;

}
