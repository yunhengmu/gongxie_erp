package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 定时任务执行记录对象 ai_agent_task_run
 *
 * <p>追加型明细（物理追加，不设逻辑删除）：记录一次任务触发的触发方式、状态、
 * 起止时间、耗时、Token 与失败原因，并关联本次执行产生的会话与助手消息。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_task_run")
public class AiAgentTaskRun extends BaseEntity {

    /**
     * 触发方式：定时
     */
    public static final String TRIGGER_CRON = "0";

    /**
     * 触发方式：手动（立即执行）
     */
    public static final String TRIGGER_MANUAL = "1";

    /**
     * 状态：成功
     */
    public static final String STATUS_SUCCESS = "0";

    /**
     * 状态：失败
     */
    public static final String STATUS_FAILED = "1";

    /**
     * 状态：执行中
     */
    public static final String STATUS_RUNNING = "2";

    /**
     * 失败原因落库的最大长度
     */
    public static final int ERROR_MSG_MAX_LENGTH = 1000;

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属任务（ai_agent_task.id）
     */
    private Long taskId;

    /**
     * 任务名称快照
     */
    private String taskName;

    /**
     * 智能体主键快照
     */
    private Long agentId;

    /**
     * 本次执行产生的会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 本次执行的助手消息（ai_message.id）
     */
    private Long messageId;

    /**
     * 触发方式（0定时 1手动）
     */
    private String triggerType;

    /**
     * 状态（0成功 1失败 2执行中）
     */
    private String status;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 输入 Token 数
     */
    private Integer promptTokens;

    /**
     * 输出 Token 数
     */
    private Integer completionTokens;

    /**
     * 总 Token 数
     */
    private Integer totalTokens;

    /**
     * 工具调用次数
     */
    private Integer toolCallCount;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 备注
     */
    private String remark;

}
