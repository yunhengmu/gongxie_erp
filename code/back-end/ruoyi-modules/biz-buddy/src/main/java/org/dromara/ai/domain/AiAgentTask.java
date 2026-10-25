package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 定时任务对象 ai_agent_task
 *
 * <p>任务定义在本项目维护，调度由应用内派发器承载（每 60 秒触发），
 * 由应用按 {@code cron_expression} 判定到点任务并执行。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_task")
public class AiAgentTask extends BaseEntity {

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 运行的智能体（ai_agent.id）
     */
    private Long agentId;

    /**
     * 智能体编码快照
     */
    private String agentCode;

    /**
     * 任务提示词（作为每轮对话的用户消息）
     */
    private String taskPrompt;

    /**
     * Cron 表达式（Spring 6 位：秒 分 时 日 月 周）
     */
    private String cronExpression;

    /**
     * 运行身份用户（会话与数据权限按该用户）
     */
    private Long runAsUserId;

    /**
     * 运行身份部门（随运行身份用户带出）
     */
    private Long runAsDeptId;

    /**
     * 上次计划执行时间（到点判定与抢占去重基准）
     */
    private LocalDateTime lastRunTime;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}
