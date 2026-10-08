package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 计划模式与任务清单配置业务对象（REQ-AI-22）。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.chat.*}，页面保存后写入 Redis 覆盖默认值，
 * 并参与智能体装配指纹判定，保存后下一次对话即按新策略装配生效。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiPlanConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用计划模式（只读设计阶段，产出计划文件并经人工确认后执行）
     */
    private boolean enabled;

    /**
     * 计划文件目录（相对智能体工作区，仅允许安全的相对目录名，禁止 {@code ..} 穿越）
     */
    @NotBlank(message = "计划文件目录不能为空")
    @Pattern(
        regexp = "^(?!.*\\.\\.)[A-Za-z0-9_][A-Za-z0-9_\\-./]{0,99}$",
        message = "计划文件目录必须是工作区内的相对目录：仅含字母、数字、下划线、短横线、点与斜杠，"
            + "不得以点或斜杠开头，且不允许 .. 目录穿越")
    private String planFileDirectory;

    /**
     * 是否启用任务清单（todo_write）
     */
    private boolean taskListEnabled;

}