package org.dromara.ai.domain.vo;

import lombok.Data;
import org.dromara.ai.domain.AiRouteRun;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 编排运行详情视图对象（REQ-AI-30）。
 *
 * <p>一次编排 + 其下的全部专家调用明细。前端「专家来源卡片」取 {@code expertCalls[].rawOutput}
 * （专家原始输出原文，不改写不裁剪），因此卡片展示与事后追溯同源。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiRouteRunDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 编排主键
     */
    private Long id;

    /**
     * 用户会话主键
     */
    private Long sessionId;

    /**
     * 本轮的助手消息主键
     */
    private Long messageId;

    /**
     * 入口智能体主键
     */
    private Long entryAgentId;

    /**
     * 路由决策（self 自答 / route 路由）
     */
    private String action;

    /**
     * 路由决策原文（JSON，含 reason / confidence）
     */
    private String decisionJson;

    /**
     * 下发给专家的任务片段（原样留档）
     */
    private String taskContext;

    /**
     * 专家调用次数（含重试与换专家）
     */
    private Integer expertCallCount;

    /**
     * 编排状态（{@link AiRouteRun#STATUS_RUNNING} 等）
     */
    private String status;

    /**
     * 降级或中止原因
     */
    private String degradeReason;

    /**
     * 编排告警汇总（只告警不阻断，REQ-AI-30 Phase 2 第五项）：
     * L3 脱敏命中项 + 事实篡改抽查不一致项；无告警时为空
     */
    private String warningText;

    /**
     * 编排总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 专家调用明细（按尝试顺序）
     */
    private List<AiRouteExpertCallVo> expertCalls;

    /**
     * 会话标题（由 ai_session 回填；管理面详情用）
     */
    private String sessionTitle;

    /**
     * 提问用户昵称（由 UserService 回填；管理面详情用）
     */
    private String userName;

    /**
     * 入口智能体名称（由 ai_agent 回填）
     */
    private String entryAgentName;

    /**
     * 入口轮输入 Token（取自助手消息）
     */
    private Long entryPromptTokens;

    /**
     * 入口轮输出 Token（取自助手消息）
     */
    private Long entryCompletionTokens;

    /**
     * 入口轮 Token（含人工确认后的整合轮；{@code messageId} 为空时为 null）
     */
    private Long entryTokens;

    /**
     * 专家轮 Token（该编排下全部专家调用的输入 + 输出之和）
     */
    private Long expertTokens;

    /**
     * 合计 Token（入口轮 + 专家轮）
     */
    private Long totalTokens;

    /**
     * 入口轮费用（元；按入口智能体当前模型单价折算，未配置单价按 0）
     */
    private BigDecimal entryCost;

    /**
     * 专家轮费用（元；按各专家当前模型单价折算，未配置单价按 0）
     */
    private BigDecimal expertCost;

    /**
     * 合计费用（元；入口轮 + 专家轮）
     */
    private BigDecimal totalCost;

    /**
     * 涉及模型是否均已配置单价：为 false 时费用偏低（未配置的按 0 计），前端据此标注「未配置单价」
     */
    private Boolean priceConfigured;

    /**
     * 结构化决策（docs/21 §6）：本轮判定来源——{@code L} 工具决策 / {@code J} 决策模型 / {@code F} 回落（工具决策兜底）。
     *
     * <p>影子期实际路由仍由工具决策完成，故本批次恒为 {@code L}；未启用时不写（NULL）。</p>
     */
    private String decisionSource;

    /**
     * 结构化决策：本轮执行模式（{@code S} 影子 / {@code G} 闸门）；为空表示本轮无判定数据
     */
    private String judgeMode;

    /**
     * 结构化决策：决策模型选中的选项（专家 {@code agent_code} / {@code self} 自答 / {@code other} 无法归属）
     */
    private String judgeChoice;

    /**
     * 结构化决策：判定置信度（0~1；仅 choice 类别带值，缺失或非 choice 时为空）
     */
    private Double judgeConfidence;

    /**
     * 结构化决策：判定耗时（毫秒）
     */
    private Integer judgeLatencyMs;

    /**
     * 结构化决策：判定输入 Token（费用口径；输出 Token 恒 0，不参与计算）
     */
    private Integer judgeTokens;

    /**
     * 结构化决策：判定不可用原因（超时 / 报错 / 越界）；判定可用时为空
     */
    private String judgeError;

}
