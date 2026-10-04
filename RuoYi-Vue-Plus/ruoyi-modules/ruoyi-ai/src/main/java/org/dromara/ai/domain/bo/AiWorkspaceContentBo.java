package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作区文件写入业务对象（REQ-AI-08）。
 *
 * @author BizBuddy
 */
@Data
public class AiWorkspaceContentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 智能体主键
     */
    @NotNull(message = "智能体不能为空")
    private Long agentId;

    /**
     * 归属用户主键（用户覆盖层的归属用户）
     */
    @NotNull(message = "用户不能为空")
    private Long userId;

    /**
     * 工作区相对路径（仅允许 AGENTS.md 与已登记的附加上下文文件）
     */
    @NotBlank(message = "文件路径不能为空")
    private String path;

    /**
     * 写入层（SHARED 共享默认 / USER 用户覆盖）
     */
    @NotBlank(message = "写入层不能为空")
    private String scope;

    /**
     * 文件正文（允许为空串，表示清空该层文件）
     */
    private String content;

}