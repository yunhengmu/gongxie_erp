package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 编排运行查询业务对象 ai_route_run（REQ-AI-30 Phase 2 第三项）
 *
 * <p>管理面「编排运行」页的筛选条件：会话 / 消息 / 入口智能体 / 提问用户 / 动作 / 状态 /
 * **参与的专家** / 时间范围。其中「参与的专家」用 {@code ai_route_expert_call} 的存在子查询实现，
 * 因此**已停用的历史专家**同样可筛（下拉选项见 {@code /ai/audit/agent/options}）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiRouteRunBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话主键（精确匹配，供从任务工作台 / 链路追踪深链直达）
     */
    private Long sessionId;

    /**
     * 助手消息主键（精确匹配）
     */
    private Long messageId;

    /**
     * 入口智能体主键
     */
    private Long entryAgentId;

    /**
     * 提问用户主键
     */
    private Long userId;

    /**
     * 路由动作（self 自答 / route 路由）
     */
    private String action;

    /**
     * 编排状态（running / success / degraded / aborted / pending）
     */
    private String status;

    /**
     * 参与的专家智能体主键（存在子查询，含已停用专家）
     */
    private Long expertAgentId;

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