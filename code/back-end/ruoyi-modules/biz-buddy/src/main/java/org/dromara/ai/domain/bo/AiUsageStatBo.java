package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * AI 用量查询业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiUsageStatBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 统计日期起（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate beginDate;

    /**
     * 统计日期止（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 模型主键
     */
    private Long modelId;

    /**
     * 用户主键
     */
    private Long userId;

    /**
     * 用量类型（0对话 1知识库入库；为空表示全部）
     */
    private String usageType;

}