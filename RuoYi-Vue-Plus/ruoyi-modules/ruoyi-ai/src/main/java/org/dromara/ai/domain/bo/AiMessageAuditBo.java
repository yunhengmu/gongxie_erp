package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话消息审计查询业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiMessageAuditBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息正文关键词（模糊匹配）
     */
    private String keyword;

    /**
     * 角色（user / assistant / system）
     */
    private String role;

    /**
     * 消息状态（0正常 1失败）
     */
    private String status;

    /**
     * 所属会话主键
     */
    private Long sessionId;

    /**
     * 会话标题（模糊匹配，经会话表过滤）
     */
    private String sessionTitle;

    /**
     * 所属智能体主键（经会话表过滤）
     */
    private Long agentId;

    /**
     * 归属用户 ID（经会话表过滤）
     */
    private Long userId;

    /**
     * 消息创建时间起（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /**
     * 消息创建时间止（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

}
