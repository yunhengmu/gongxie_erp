package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具权限决策日志视图对象
 *
 * @author BizBuddy
 */
@Data
public class AiPermissionDecisionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属会话主键
     */
    private Long sessionId;

    /**
     * 会话标题
     */
    private String sessionTitle;

    /**
     * 所属助手消息主键
     */
    private Long messageId;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 触发用户 ID
     */
    private Long userId;

    /**
     * 触发用户昵称
     */
    private String userName;

    /**
     * 工具编码
     */
    private String toolCode;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 决策（0允许 1需确认 2拒绝）
     */
    private String decision;

    /**
     * 决策来源
     */
    private String decisionSource;

    /**
     * 命中的规则名
     */
    private String matchedRule;

    /**
     * 决策说明
     */
    private String reason;

    /**
     * 工具入参 JSON
     */
    private String toolInput;

    /**
     * 人工确认结果（0拒绝 1确认）
     */
    private String confirmed;

    /**
     * 确认作用范围（once / remember）
     */
    private String confirmSource;

    /**
     * 工具执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}