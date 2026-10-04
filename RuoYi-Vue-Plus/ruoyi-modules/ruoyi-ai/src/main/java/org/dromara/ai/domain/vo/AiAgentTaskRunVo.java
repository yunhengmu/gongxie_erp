package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiAgentTaskRun;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 定时任务执行记录视图对象 ai_agent_task_run
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiAgentTaskRun.class)
public class AiAgentTaskRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属任务主键
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

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
