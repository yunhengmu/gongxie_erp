package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiRouteExpertCall;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 专家调用明细视图对象 ai_route_expert_call（REQ-AI-30）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiRouteExpertCall.class)
public class AiRouteExpertCallVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属编排主键
     */
    private Long routeRunId;

    /**
     * 被调用的专家智能体主键
     */
    private Long expertAgentId;

    /**
     * 专家名称（由 ai_agent 回填，便于前端渲染历史专家卡片）
     */
    private String expertAgentName;

    /**
     * 专家编码（由 ai_agent 回填，便于前端渲染历史专家卡片）
     */
    private String expertAgentKey;

    /**
     * 专家独立会话标识
     */
    private String expertSessionId;

    /**
     * 第几次尝试
     */
    private Integer attemptNo;

    /**
     * 调用类型（first / retry / switch）
     */
    private String callKind;

    /**
     * 本次下发的输出契约
     */
    private String contractJson;

    /**
     * 专家原始输出原文（不改写、不裁剪）
     */
    private String rawOutput;

    /**
     * 通过契约校验后的结构化结果
     */
    private String parsedOutput;

    /**
     * 结构校验失败原因
     */
    private String validationError;

    /**
     * 调用状态（success / invalid / failed / timeout）
     */
    private String status;

    /**
     * 该次调用的输入 Token
     */
    private Long promptTokens;

    /**
     * 该次调用的输出 Token
     */
    private Long completionTokens;

    /**
     * 该次调用耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
