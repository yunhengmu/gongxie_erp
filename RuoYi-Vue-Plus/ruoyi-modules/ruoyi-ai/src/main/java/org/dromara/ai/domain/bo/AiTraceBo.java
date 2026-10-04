package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 链路追踪查询业务对象 ai_trace（REQ-AI-28）
 *
 * @author BizBuddy
 */
@Data
public class AiTraceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话主键（精确匹配，供从会话/任务直达链路）
     */
    private Long sessionId;

    /**
     * 助手消息主键（精确匹配）
     */
    private Long messageId;

    /**
     * 父链路主键（精确匹配；用于筛出某次编排下的全部专家子链路，REQ-AI-30）
     */
    private Long parentTraceId;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 任务来源（chat 实时对话 / task 定时任务）
     */
    private String source;

    /**
     * 链路状态（running / pending_confirm / success / failed / aborted）
     */
    private String status;

    /**
     * 模型标识（模糊匹配，用于排查某模型版本的链路）
     */
    private String modelCode;

    /**
     * 创建时间起（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /**
     * 创建时间止（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

}