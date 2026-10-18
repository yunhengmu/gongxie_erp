package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiDailyDigest;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 平台运行日报视图对象 ai_daily_digest
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiDailyDigest.class)
public class AiDailyDigestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 日报日期
     */
    private LocalDate digestDate;

    /**
     * 结论级别（0平稳 1需关注 2需处理；解析失败为 null）
     */
    private String level;

    /**
     * 日报正文（人话版）
     */
    private String digestText;

    /**
     * 当日总 Token
     */
    private Long usageTotalTokens;

    /**
     * 当日请求（对话轮次）数
     */
    private Integer usageRequestCount;

    /**
     * 当日工具调用次数
     */
    private Integer usageToolCallCount;

    /**
     * Token 环比（%）
     */
    private BigDecimal usageComparePct;

    /**
     * 配额使用率（%；配额为 0 即不限时为 null）
     */
    private BigDecimal quotaUsagePct;

    /**
     * 定时任务执行次数
     */
    private Integer taskRunCount;

    /**
     * 定时任务失败次数
     */
    private Integer taskFailCount;

    /**
     * 工作流待办积压条数
     */
    private Integer backlogCount;

    /**
     * 积压最多的节点名
     */
    private String backlogTopNode;

    /**
     * 最久滞留分钟数
     */
    private Integer backlogMaxWaitMinutes;

    /**
     * 权限决策-允许次数
     */
    private Integer permAllowCount;

    /**
     * 权限决策-需确认次数
     */
    private Integer permAskCount;

    /**
     * 权限决策-拒绝次数
     */
    private Integer permDenyCount;

    /**
     * 登录成功次数
     */
    private Integer loginSuccessCount;

    /**
     * 登录失败次数
     */
    private Integer loginFailCount;

    /**
     * 登录异动次数（异地或新 IP）
     */
    private Integer loginAnomalyCount;

    /**
     * 来源任务执行记录（ai_agent_task_run.id）
     */
    private Long taskRunId;

    /**
     * 来源会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 备注（结论级别解析失败时的降级说明）
     */
    private String remark;

    /**
     * 创建时间（日报落库时间）
     */
    private LocalDateTime createTime;

}
