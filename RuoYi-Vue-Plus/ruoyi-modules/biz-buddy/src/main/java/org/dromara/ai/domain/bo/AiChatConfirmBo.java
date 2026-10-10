package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * HITL 工具确认业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiChatConfirmBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 暂停时落库的助手消息主键
     */
    @NotNull(message = "待确认消息不能为空")
    private Long messageId;

    /**
     * 是否确认执行（false 表示拒绝）
     */
    @NotNull(message = "确认结果不能为空")
    private Boolean confirmed;

    /**
     * 是否记住该工具（确认后写入「智能体 × 工具」信任决策，之后该工具免人工确认）
     */
    private Boolean remember;

    /**
     * 自定义拒绝原因（可选；仅 {@code confirmed=false} 时生效）。
     *
     * <p>回传给模型使其知道「为什么被拒」，并写入权限决策日志，避免用户拒绝后模型重复请求同一工具。</p>
     */
    private String rejectReason;

}