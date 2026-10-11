package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiRouteRun;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 编排运行视图对象 ai_route_run（REQ-AI-30）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiRouteRun.class)
public class AiRouteRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
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
     * 提问用户
     */
    private Long userId;

    /**
     * 路由决策（self 自答 / route 路由）
     */
    private String action;

    /**
     * 路由决策原文
     */
    private String decisionJson;

    /**
     * 下发给专家的任务片段
     */
    private String taskContext;

    /**
     * 专家调用次数
     */
    private Integer expertCallCount;

    /**
     * 编排状态（running / success / degraded / aborted）
     */
    private String status;

    /**
     * 降级或中止原因
     */
    private String degradeReason;

    /**
     * 编排总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 入口智能体名称（由 ai_agent 回填；管理面列表用）
     */
    private String entryAgentName;

    /**
     * 入口智能体编码（由 ai_agent 回填）
     */
    private String entryAgentCode;

    /**
     * 会话标题（由 ai_session 回填）
     */
    private String sessionTitle;

    /**
     * 提问用户昵称（由 UserService 回填）
     */
    private String userName;

    /**
     * 本轮参与的专家名称（由专家调用明细聚合，顿号连接；用于列表快速识别路由去向）
     */
    private String expertAgentNames;

    /**
     * 入口轮 Token（含人工确认后的整合轮；message_id 为空时为 null）
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

}
