package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 审计与运行数据保留清理用 Mapper（计数 + 物理删除）。
 *
 * <p>这些表是明细 / 追加表，按时间清理必须**物理删除**：{@code ai_route_run} 实体带
 * {@code @TableLogic}，走 MyBatis-Plus 的 {@code delete} 会退化为逻辑删除、无法真实回收空间，
 * 故此处用显式 SQL 直删。**清理范围不含会话正文 {@code ai_message}**（会话内容属业务数据，
 * 仍由用户在会话页自行删除）。</p>
 *
 * <p>删除按表各自独立执行、不加事务：单表 DELETE 自身原子，避免跨表长事务长时间持锁。</p>
 *
 * @author BizBuddy
 */
public interface AiAuditRetentionMapper {

    /**
     * 统计超期的链路追踪行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_trace where create_time < #{cutoff}")
    long countTrace(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除超期的链路追踪行（含子链路，同表按时间一并清理）
     *
     * @param cutoff 保留截止时间
     * @return 删除行数
     */
    @Delete("delete from ai_trace where create_time < #{cutoff}")
    int purgeTrace(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 统计超期的工具调用明细行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_tool_call_log where create_time < #{cutoff}")
    long countToolCallLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除超期的工具调用明细
     *
     * @param cutoff 保留截止时间
     * @return 删除行数
     */
    @Delete("delete from ai_tool_call_log where create_time < #{cutoff}")
    int purgeToolCallLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 统计超期的权限决策日志行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_permission_decision_log where create_time < #{cutoff}")
    long countPermissionDecisionLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除超期的权限决策日志
     *
     * @param cutoff 保留截止时间
     * @return 删除行数
     */
    @Delete("delete from ai_permission_decision_log where create_time < #{cutoff}")
    int purgePermissionDecisionLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 统计超期的定时任务执行记录行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_agent_task_run where create_time < #{cutoff}")
    long countAgentTaskRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除超期的定时任务执行记录
     *
     * @param cutoff 保留截止时间
     * @return 删除行数
     */
    @Delete("delete from ai_agent_task_run where create_time < #{cutoff}")
    int purgeAgentTaskRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 统计超期的编排运行行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_route_run where create_time < #{cutoff}")
    long countRouteRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除超期的编排运行（跳过 {@code @TableLogic}，直达物理删除）
     *
     * @param cutoff 保留截止时间
     * @return 删除行数
     */
    @Delete("delete from ai_route_run where create_time < #{cutoff}")
    int purgeRouteRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 统计超期的专家调用明细行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_route_expert_call where create_time < #{cutoff}")
    long countRouteExpertCall(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除超期的专家调用明细（编排运行的子表，一并按时间清理）
     *
     * @param cutoff 保留截止时间
     * @return 删除行数
     */
    @Delete("delete from ai_route_expert_call where create_time < #{cutoff}")
    int purgeRouteExpertCall(@Param("cutoff") LocalDateTime cutoff);

}
