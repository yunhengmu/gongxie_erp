package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiAgentTask;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 定时任务业务对象 ai_agent_task
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiAgentTask.class, reverseConvertGenerate = false)
public class AiAgentTaskBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 任务名称
     */
    @NotBlank(message = "任务名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "任务名称不能超过 100 个字符", groups = {AddGroup.class, EditGroup.class})
    private String taskName;

    /**
     * 运行的智能体（ai_agent.id）
     */
    @NotNull(message = "智能体不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long agentId;

    /**
     * 任务提示词（作为每轮对话的用户消息）
     */
    @NotBlank(message = "任务提示词不能为空", groups = {AddGroup.class, EditGroup.class})
    private String taskPrompt;

    /**
     * Cron 表达式（Spring 6 位：秒 分 时 日 月 周）
     */
    @NotBlank(message = "Cron 表达式不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "Cron 表达式不能超过 100 个字符", groups = {AddGroup.class, EditGroup.class})
    private String cronExpression;

    /**
     * 运行身份用户（会话与数据权限按该用户）
     */
    @NotNull(message = "运行身份用户不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long runAsUserId;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 列表查询用：按任务名称模糊匹配
     */
    private String keyword;

}
