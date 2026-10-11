package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 安全事件查询业务对象 ai_safety_event
 *
 * <p>管理面「安全事件」页的筛选条件：场景 / 处置动作 / 触发用户 / 时间范围。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiSafetyEventBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 场景（input 用户输入 / output 模型输出 / channel_out 渠道出站）
     */
    private String scene;

    /**
     * 处置动作（log 只记录 / mask 脱敏放行 / block 阻断）
     */
    private String action;

    /**
     * 触发用户主键
     */
    private Long userId;

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