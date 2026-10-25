package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话查询业务对象 ai_session
 *
 * @author BizBuddy
 */
@Data
public class AiSessionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话标题（模糊匹配）
     */
    private String sessionTitle;

    /**
     * 会话主键（精确匹配，供从定时任务执行记录直达会话）
     */
    private Long sessionId;

    /**
     * 会话来源（chat 手动对话 / task 定时任务）
     */
    private String source;

    /**
     * 所属智能体（ai_agent.id）
     */
    private Long agentId;

    /**
     * 归属用户 ID（仅管理端检索时使用；对话页强制取当前用户）
     */
    private Long userId;

    /**
     * 状态（0进行中 1已结束）
     */
    private String status;

    /**
     * 最后活跃时间起（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /**
     * 最后活跃时间止（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /**
     * 仅查询当前用户的会话（管理端检索时的快捷开关）
     */
    private Boolean mine;

}
