package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具调用明细审计视图对象（明细 + 所属会话上下文）
 *
 * @author BizBuddy
 */
@Data
public class AiToolCallAuditVo implements Serializable {

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
     * 发起该工具调用的智能体主键（明细行自身归属：入口轮＝会话智能体，专家执行＝专家；
     * 历史行为空，此时回落会话智能体。REQ-AI-30 Phase 2）
     */
    private Long agentId;

    /**
     * 发起该工具调用的智能体名称（由 {@code agent_id} 回填，回落会话智能体）
     */
    private String agentName;

    /**
     * 所属助手消息主键
     */
    private Long messageId;

    /**
     * 触发用户 ID
     */
    private Long userId;

    /**
     * 触发用户昵称
     */
    private String userName;

    /**
     * AgentScope toolCallId
     */
    private String toolCallId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 入参 JSON
     */
    private String toolInput;

    /**
     * 结果摘要
     */
    private String toolOutput;

    /**
     * 结果状态
     */
    private String state;

    /**
     * 工具执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
