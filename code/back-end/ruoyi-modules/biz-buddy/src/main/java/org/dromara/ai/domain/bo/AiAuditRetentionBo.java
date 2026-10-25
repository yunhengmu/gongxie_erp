package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审计与运行数据保留策略（运行期配置：Redis 覆盖 {@code bizbuddy.ai.retention.*} 默认值）。
 *
 * <p>只描述「保留天数」一项：{@code 0} 表示不限、**不执行清理**（默认值，与改造前行为一致）。</p>
 *
 * <p>清理范围为**运行与审计明细表**：{@code ai_trace} / {@code ai_tool_call_log} /
 * {@code ai_permission_decision_log} / {@code ai_agent_task_run} / {@code ai_route_run} /
 * {@code ai_route_expert_call}；**不含会话正文 {@code ai_message}**（会话内容属业务数据，
 * 仍由用户在会话页自行删除）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiAuditRetentionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 审计 / 运行明细保留天数（0＝不限，不清理）
     */
    @Min(value = 0, message = "保留天数不能为负")
    @Max(value = 3650, message = "保留天数不能超过 3650")
    private int retentionDays;

}
