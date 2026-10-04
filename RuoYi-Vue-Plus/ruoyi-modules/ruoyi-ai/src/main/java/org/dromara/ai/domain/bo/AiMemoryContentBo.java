package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 长期记忆文件写入业务对象（REQ-AI-09）。
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryContentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 智能体主键
     */
    @NotNull(message = "智能体不能为空")
    private Long agentId;

    /**
     * 记忆归属用户主键（记忆按用户隔离）
     */
    @NotNull(message = "用户不能为空")
    private Long userId;

    /**
     * 工作区相对路径（仅允许 MEMORY.md 或 memory/&lt;名称&gt;.md）
     */
    @NotBlank(message = "文件路径不能为空")
    private String path;

    /**
     * 文件正文（允许为空串，表示清空该文件）
     */
    private String content;

}