package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiAgentTask;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 定时任务视图对象 ai_agent_task
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiAgentTask.class)
public class AiAgentTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 运行的智能体主键
     */
    private Long agentId;

    /**
     * 智能体编码快照
     */
    private String agentCode;

    /**
     * 智能体名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String agentName;

    /**
     * 智能体状态（附加字段，由 Service 填充；1 表示已停用，任务将无法执行）
     */
    @TableField(exist = false)
    private String agentStatus;

    /**
     * 任务提示词
     */
    private String taskPrompt;

    /**
     * Cron 表达式
     */
    private String cronExpression;

    /**
     * 运行身份用户
     */
    private Long runAsUserId;

    /**
     * 运行身份用户昵称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String runAsUserName;

    /**
     * 运行身份部门
     */
    private Long runAsDeptId;

    /**
     * 运行身份部门名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String runAsDeptName;

    /**
     * 上次计划执行时间
     */
    private LocalDateTime lastRunTime;

    /**
     * 预计下次执行时间（附加字段，由 Service 按 Cron 计算）
     */
    @TableField(exist = false)
    private LocalDateTime nextRunTime;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
