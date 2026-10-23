package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 显式记忆新增参数：用户明确要求记住的个人事实。
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryDeclaredBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 智能体主键（专家会自动归并到入口智能体的记忆命名空间）
     */
    @NotNull(message = "智能体不能为空")
    private Long agentId;

    /**
     * 记忆归属用户主键
     */
    @NotNull(message = "用户不能为空")
    private Long userId;

    /**
     * 事实正文（平台会再次净化）
     */
    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 500, message = "记忆内容不能超过 500 字")
    private String fact;

    /**
     * 用户原话证据（可为空）
     */
    @Size(max = 1000, message = "证据不能超过 1000 字")
    private String evidence;
}
