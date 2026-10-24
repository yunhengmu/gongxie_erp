package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 编排运行统计视图对象（REQ-AI-30 Phase 2 第三项）。
 *
 * <p>与「编排运行」列表**同一筛选条件**下的汇总口径：</p>
 * <ul>
 *   <li><b>路由命中率</b>＝{@code (route + pinned) ÷ total}（{@code action} 在决策后即落库，含未终结的编排；
 *       {@code pinned} 为用户指定专家，与 {@code route} 同属「路由类」，见 REQ-AI-31）；</li>
 *   <li><b>降级率</b>＝{@code degraded ÷ (success + degraded + aborted)}——running / pending 属未终结，
 *       不计入分母，否则 HITL 暂停会把降级率拉低；</li>
 *   <li><b>Token 分三份</b>：专家轮取自 {@code ai_route_expert_call}，入口轮取自 {@code ai_message.total_tokens}
 *       （含入口轮与人工确认后的整合轮，{@code message_id} 为空的编排不计），合计为两者之和。</li>
 * </ul>
 *
 * @author BizBuddy
 */
@Data
public class AiRouteRunStatsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 编排总数
     */
    private Long totalCount;

    /**
     * 自答数（action=self）
     */
    private Long selfCount;

    /**
     * 路由数（action=route 与 action=pinned 合计，同属「路由类」）
     */
    private Long routeCount;

    /**
     * 其中用户指定（action=pinned，REQ-AI-31）
     */
    private Long pinnedCount;

    /**
     * 执行中
     */
    private Long runningCount;

    /**
     * 成功
     */
    private Long successCount;

    /**
     * 降级
     */
    private Long degradedCount;

    /**
     * 中止
     */
    private Long abortedCount;

    /**
     * 待人工确认（HITL 暂停）
     */
    private Long pendingCount;

    /**
     * 路由命中率（(route + pinned) ÷ total，百分比，保留 1 位；total 为 0 时为 null）
     */
    private Double routeHitRate;

    /**
     * 降级率（degraded ÷ (success + degraded + aborted)，百分比，保留 1 位；已终结为 0 时为 null）
     */
    private Double degradeRate;

    /**
     * 专家调用总次数（含重试与换专家，取自 expert_call_count 求和）
     */
    private Long expertCallTotal;

    /**
     * 编排总耗时（毫秒）
     */
    private Long totalDurationMs;

    /**
     * 平均耗时（毫秒，保留整数；total 为 0 时为 null）
     */
    private Long avgDurationMs;

    /**
     * 专家轮输入 Token
     */
    private Long expertPromptTokens;

    /**
     * 专家轮输出 Token
     */
    private Long expertCompletionTokens;

    /**
     * 入口轮 Token（含人工确认后的整合轮；message_id 为空的编排不计）
     */
    private Long entryTokens;

    /**
     * 合计 Token（专家轮 + 入口轮）
     */
    private Long totalTokens;

    /**
     * 合计费用（元；入口轮 + 专家轮，按当前模型单价折算，未配置单价按 0）
     */
    private BigDecimal totalCost;

    /**
     * 入口轮费用（元）
     */
    private BigDecimal entryCost;

    /**
     * 专家轮费用（元）
     */
    private BigDecimal expertCost;

}