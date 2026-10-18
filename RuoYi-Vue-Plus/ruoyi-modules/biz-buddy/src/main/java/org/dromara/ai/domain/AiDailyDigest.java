package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AI 平台运行日报对象 ai_daily_digest（每天一条）。
 *
 * <p>由「任务产出投递器」按 {@code digest_date} **upsert** 写入（系统代码写，不给 AI 写工具）：
 * 同日重跑只更新不新增。五块快照取自 {@code PlatformDigestService}（与工具同一口径），
 * {@code digest_text} 由模型产出（落库保真，便于回看与推送失败补投）。</p>
 *
 * <p>{@code level} 由投递器从模型正文全文匹配「结论级别：X」解析；解析失败记 {@code null}
 * 并把该行备注标为降级（见 {@code TaskOutputDispatcher}）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_daily_digest")
public class AiDailyDigest extends BaseEntity {

    /**
     * 结论级别：平稳
     */
    public static final String LEVEL_STABLE = "0";

    /**
     * 结论级别：需关注
     */
    public static final String LEVEL_WATCH = "1";

    /**
     * 结论级别：需处理
     */
    public static final String LEVEL_ACTION = "2";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 日报日期（唯一；重跑按日期 upsert）
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
     * Token 环比（%；无上日数据时为 null）
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
     * 备注
     */
    private String remark;

}
