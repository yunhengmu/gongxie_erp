package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 「重新生成」预检结果视图。
 *
 * <p>前端在展示按钮与点击执行前都以此为准：不允许时给出可读原因与命中的写工具，
 * 避免前端自行猜测判定规则。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiRegenerateCheckVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 原因码：允许
     */
    public static final String CODE_OK = "OK";

    /**
     * 原因码：会话已结束
     */
    public static final String CODE_SESSION_CLOSED = "SESSION_CLOSED";

    /**
     * 原因码：目标消息不存在或不属于该会话
     */
    public static final String CODE_ROUND_NOT_FOUND = "ROUND_NOT_FOUND";

    /**
     * 原因码：只允许重新生成最后一轮
     */
    public static final String CODE_ROUND_NOT_LAST = "ROUND_NOT_LAST";

    /**
     * 原因码：该轮仍在生成中
     */
    public static final String CODE_ROUND_STREAMING = "ROUND_STREAMING";

    /**
     * 原因码：存在待人工确认的工具调用
     */
    public static final String CODE_ROUND_PENDING_CONFIRM = "ROUND_PENDING_CONFIRM";

    /**
     * 原因码：会话有进行中的执行
     */
    public static final String CODE_CONVERSATION_BUSY = "CONVERSATION_BUSY";

    /**
     * 原因码：该轮执行过写操作
     */
    public static final String CODE_WRITE_TOOL_USED = "WRITE_TOOL_USED";

    /**
     * 原因码：该轮调用了未登记工具，无法确认只读
     */
    public static final String CODE_UNKNOWN_TOOL_USED = "UNKNOWN_TOOL_USED";

    /**
     * 原因码：模型上下文回退失败
     */
    public static final String CODE_CONTEXT_RESTORE_FAILED = "CONTEXT_RESTORE_FAILED";

    /**
     * 是否允许重新生成
     */
    private boolean allowed;

    /**
     * 原因码（见本类常量）
     */
    private String reasonCode;

    /**
     * 面向用户的原因说明
     */
    private String reason;

    /**
     * 本轮的助手消息主键（旧回答将以逻辑删除保留审计行）
     */
    private List<Long> assistantMessageIds;

    /**
     * 命中的写操作工具（允许时为空）
     */
    private List<WriteTool> writeTools;

    /**
     * 命中的写操作工具摘要。
     */
    @Data
    public static class WriteTool implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 工具编码
         */
        private String toolCode;

        /**
         * 工具中文名（未登记时回退为编码）
         */
        private String toolName;

        /**
         * 调用结果状态（SUCCESS / ERROR / INTERRUPTED / DENIED）
         */
        private String state;

        /**
         * 是否未在 ai_tool 登记
         */
        private boolean unknown;

    }

}
