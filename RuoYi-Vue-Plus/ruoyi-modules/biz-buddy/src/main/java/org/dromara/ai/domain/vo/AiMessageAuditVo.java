package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话消息审计视图对象（消息 + 所属会话上下文）
 *
 * @author BizBuddy
 */
@Data
public class AiMessageAuditVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息主键
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
     * 所属智能体主键
     */
    private Long agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 归属用户 ID
     */
    private Long userId;

    /**
     * 归属用户昵称
     */
    private String userName;

    /**
     * 角色（user / assistant / system）
     */
    private String role;

    /**
     * 消息正文
     */
    private String content;

    /**
     * AgentScope replyId
     */
    private String replyId;

    /**
     * 状态（0正常 1失败）
     */
    private String status;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 本轮工具调用次数
     */
    private Integer toolCallCount;

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
     * 本轮耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
