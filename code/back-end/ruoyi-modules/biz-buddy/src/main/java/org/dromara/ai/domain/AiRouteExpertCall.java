package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 专家调用明细对象 ai_route_expert_call（REQ-AI-30）
 *
 * <p>一次专家调用一行，是**来源标注与追溯的载体**：{@code raw_output} 保真保存专家原始输出原文
 * （不改写、不裁剪），前端专家卡片的可展开内容即取自此列。</p>
 *
 * <p>追加型明细，物理删除，故不继承 {@code BaseEntity}（避免引入本表不存在的更新列，
 * 与 {@link AiTrace} 同款）。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_route_expert_call")
public class AiRouteExpertCall implements Serializable {

    /**
     * 调用类型：首次
     */
    public static final String KIND_FIRST = "first";

    /**
     * 调用类型：同专家重试
     */
    public static final String KIND_RETRY = "retry";

    /**
     * 调用类型：换专家
     */
    public static final String KIND_SWITCH = "switch";

    /**
     * 调用类型：人工确认后续跑（同一专家的同一次尝试，携带 ConfirmResult 恢复被 HITL 暂停的会话）
     */
    public static final String KIND_RESUME = "resume";

    /**
     * 调用状态：成功（通过契约校验）
     */
    public static final String STATUS_SUCCESS = "success";

    /**
     * 调用状态：结构不合格（未通过契约校验）
     */
    public static final String STATUS_INVALID = "invalid";

    /**
     * 调用状态：调用失败（模型或链路错误）
     */
    public static final String STATUS_FAILED = "failed";

    /**
     * 调用状态：超时
     */
    public static final String STATUS_TIMEOUT = "timeout";

    /**
     * 调用状态：暂停（命中「需确认」工具，等待用户人工确认后由恢复段续跑）
     */
    public static final String STATUS_PENDING_CONFIRM = "pending_confirm";

    /**
     * 调用状态：已取消（用户「停止生成」/ SSE 断连中断了本次专家调用）
     */
    public static final String STATUS_CANCELLED = "cancelled";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属编排（ai_route_run.id）
     */
    private Long routeRunId;

    /**
     * 被调用的专家智能体主键（ai_agent.id）
     */
    private Long expertAgentId;

    /**
     * 专家独立会话标识（routeRunId#expertAgentId），与用户会话完全隔离
     */
    private String expertSessionId;

    /**
     * 第几次尝试（从 1 起）
     */
    private Integer attemptNo;

    /**
     * 调用类型（first 首次 / retry 同专家重试 / switch 换专家）
     */
    private String callKind;

    /**
     * 本次下发的输出契约（第 3 次尝试为放宽后的 partial 契约）
     */
    private String contractJson;

    /**
     * 专家原始输出原文（不改写、不裁剪）
     */
    private String rawOutput;

    /**
     * 通过契约校验后的结构化结果（JSON 文本）
     */
    private String parsedOutput;

    /**
     * 结构校验失败原因（重试时回传给专家）
     */
    private String validationError;

    /**
     * 调用状态（success / invalid / failed / timeout / pending_confirm / cancelled）
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
     * 创建部门
     */
    private Long createDept;

    /**
     * 创建者
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
