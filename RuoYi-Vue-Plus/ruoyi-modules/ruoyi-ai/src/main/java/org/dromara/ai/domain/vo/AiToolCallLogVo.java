package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiToolCallLog;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 工具调用明细视图对象 ai_tool_call_log
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiToolCallLog.class)
public class AiToolCallLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 所属助手消息（ai_message.id）
     */
    private Long messageId;

    /**
     * 触发用户 ID
     */
    private Long userId;

    /**
     * 发起该工具调用的智能体主键（入口轮＝会话智能体，专家调用＝专家；历史行为空）
     */
    private Long agentId;

    /**
     * AgentScope toolCallId
     */
    private String toolCallId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 入参 JSON（由 tool_call delta 增量拼接）
     */
    private String toolInput;

    /**
     * 结果摘要（截断后）
     */
    private String toolOutput;

    /**
     * 结果状态（SUCCESS / ERROR / INTERRUPTED / DENIED）
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
