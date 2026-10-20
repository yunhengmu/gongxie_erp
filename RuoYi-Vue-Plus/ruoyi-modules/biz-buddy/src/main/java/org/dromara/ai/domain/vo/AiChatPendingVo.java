package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 待确认的工具调用视图对象（供对话页刷新后恢复确认卡片）
 *
 * @author BizBuddy
 */
@Data
public class AiChatPendingVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 暂停时落库的助手消息主键
     */
    private Long messageId;

    /**
     * AgentScope replyId
     */
    private String replyId;

    /**
     * 剩余有效期（秒），0 表示未知
     */
    private Long expiresInSec;

    /**
     * 是否已过期（Redis 暂存已失效，无法再确认）
     */
    private Boolean expired;

    /**
     * 待确认的工具调用
     */
    private List<ToolCall> toolCalls;

    /**
     * 待确认的工具调用。
     */
    @Data
    public static class ToolCall implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * AgentScope 工具调用 ID
         */
        private String toolCallId;

        /**
         * 工具名称（工具编码）
         */
        private String toolName;

        /**
         * 工具中文显示名；未在 ai_tool 登记（如 MCP 别名 mcp_<hash>）时等于 {@link #toolName}
         */
        private String toolLabel;

        /**
         * 工具注册说明；未登记时为空串
         */
        private String toolDesc;

        /**
         * 工具入参
         */
        private Map<String, Object> input;

    }

}