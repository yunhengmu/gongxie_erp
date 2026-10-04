package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具权限决策日志查询业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiPermissionDecisionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属会话主键
     */
    private Long sessionId;

    /**
     * 工具编码（模糊匹配）
     */
    private String toolCode;

    /**
     * 决策（0允许 1需确认 2拒绝）
     */
    private String decision;

    /**
     * 人工确认结果（0拒绝 1确认）
     */
    private String confirmed;

    /**
     * 触发用户 ID
     */
    private Long userId;

    /**
     * 智能体主键
     */
    private Long agentId;

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