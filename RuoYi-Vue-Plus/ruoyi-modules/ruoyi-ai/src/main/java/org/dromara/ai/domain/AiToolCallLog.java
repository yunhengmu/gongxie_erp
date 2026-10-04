package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 工具调用明细对象 ai_tool_call_log
 *
 * <p>追加型明细，物理删除，不设 del_flag。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool_call_log")
public class AiToolCallLog extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
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
     * 发起该工具调用的智能体主键（入口轮＝会话智能体，专家调用＝专家，无人值守＝对应智能体；历史行为空）
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
     * 备注
     */
    private String remark;

}
