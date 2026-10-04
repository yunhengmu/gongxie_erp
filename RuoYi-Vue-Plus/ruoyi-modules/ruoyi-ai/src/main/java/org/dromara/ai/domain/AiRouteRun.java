package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 编排运行对象 ai_route_run（REQ-AI-30）
 *
 * <p>入口智能体（小Z）的**每一轮编排一行**：自答也落一行（action=self），便于统计路由命中率与降级率。
 * 与既有治理表一致，本表为逻辑删除并带完整审计列。</p>
 *
 * <p>专家调用的明细在 {@link AiRouteExpertCall}（一对多，按 {@code route_run_id} 关联）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_route_run")
public class AiRouteRun extends BaseEntity {

    /**
     * 编排动作：自答（入口智能体直接回答，不调用专家）
     */
    public static final String ACTION_SELF = "self";

    /**
     * 编排动作：路由（调用专家并由入口智能体整合）
     */
    public static final String ACTION_ROUTE = "route";

    /**
     * 编排动作：指定路由（用户指定专家，REQ-AI-31）
     *
     * <p>与 {@link #ACTION_ROUTE} 同属「路由类」——统计口径把二者合并计入路由命中率
     * （{@code (route + pinned) ÷ total}），另单列「其中用户指定」。</p>
     */
    public static final String ACTION_PINNED = "pinned";

    /**
     * 编排状态：进行中
     */
    public static final String STATUS_RUNNING = "running";

    /**
     * 编排状态：成功（专家结论达契约并已整合）
     */
    public static final String STATUS_SUCCESS = "success";

    /**
     * 编排状态：降级（未拿到合格专家结论，由入口智能体自答并声明）
     */
    public static final String STATUS_DEGRADED = "degraded";

    /**
     * 编排状态：中止（异常终止）
     */
    public static final String STATUS_ABORTED = "aborted";

    /**
     * 编排状态：暂停（专家命中「需确认」工具，等待用户人工确认后由恢复段续跑）
     */
    public static final String STATUS_PENDING = "pending";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 用户会话主键（ai_session.id）
     */
    private Long sessionId;

    /**
     * 本轮的助手消息主键（ai_message.id）
     */
    private Long messageId;

    /**
     * 入口智能体主键（本期恒为小Z）
     */
    private Long entryAgentId;

    /**
     * 提问用户
     */
    private Long userId;

    /**
     * 路由决策（self 自答 / route 路由 / pinned 用户指定专家，REQ-AI-31）
     */
    private String action;

    /**
     * 路由决策原文（含 reason / confidence，用于复盘决策质量）
     */
    private String decisionJson;

    /**
     * 下发给专家的任务片段（原样留档，用于事后核对「到底传了什么」）
     */
    private String taskContext;

    /**
     * 本次编排的专家调用次数（含重试与换专家）
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
     * 编排告警汇总（REQ-AI-30 Phase 2 第五项，**只告警不阻断**）。
     *
     * <p>两类来源：L3 脱敏命中项（按类型计数）+ 事实篡改抽查不一致项（专家关键数字未在最终回答中出现）。
     * 与 {@code degradeReason} 的分工：前者记「为什么降级 / 中止」，本字段记「结论质量提示」，
     * 两者可同时非空；无告警时为空。</p>
     */
    private String warningText;

    /**
     * 编排总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}
