package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具调用明细审计查询业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiToolCallAuditBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关键词（模糊匹配 工具名 / 入参 / 出参）
     */
    private String keyword;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 结果状态（SUCCESS / ERROR / INTERRUPTED / DENIED）
     */
    private String state;

    /**
     * 所属会话主键
     */
    private Long sessionId;

    /**
     * 发起该工具调用的智能体主键（精确匹配明细行的 agent_id；含已停用智能体，选项见
     * {@code GET /ai/audit/agent/options}。REQ-AI-30 Phase 2）
     */
    private Long agentId;

    /**
     * 触发用户 ID
     */
    private Long userId;

    /**
     * 归属用户 ID（经会话表过滤）
     */
    private Long ownerUserId;

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
