package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.ai.domain.vo.PlatformDigestRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台运行日报统计 Mapper（只读）。
 *
 * <p><b>方言中立</b>：所有日期 / 时间窗口由调用方（服务层）在 Java 侧算好后传入，
 * 不出现 {@code now()}、{@code interval}、{@code date_format} 等方言函数；
 * 聚合一律用 {@code sum(case when …)} 且**不加 coalesce** —— 无匹配行时返回 {@code null}，
 * 作为「该块当日无数据」的探针（与 {@code PlatformDigestRow} 的探针约定配套）。</p>
 *
 * @author BizBuddy
 */
public interface PlatformDigestMapper {

    /**
     * ① 当日用量合计。
     *
     * @param statDate 统计日期（{@code ai_usage_stat.stat_date}）
     * @return 用量块（无行时 {@code usageTotalTokens} 为 null）
     */
    @Select("""
        select sum(total_tokens)    as usage_total_tokens,
               sum(request_count)   as usage_request_count,
               sum(tool_call_count) as usage_tool_call_count,
               sum(cost)            as usage_cost
        from ai_usage_stat
        where stat_date = #{statDate}
        """)
    PlatformDigestRow selectUsage(@Param("statDate") LocalDate statDate);

    /**
     * ② 当日定时任务执行情况。
     *
     * @param dayStart 当日 00:00
     * @param dayEnd   次日 00:00
     * @return 任务块（无行时 {@code taskFailCount} 为 null）
     */
    @Select("""
        select count(*)                                     as task_run_count,
               sum(case when status = '1' then 1 else 0 end) as task_fail_count
        from ai_agent_task_run
        where create_time >= #{dayStart} and create_time < #{dayEnd}
        """)
    PlatformDigestRow selectTaskRun(@Param("dayStart") LocalDateTime dayStart,
                                    @Param("dayEnd") LocalDateTime dayEnd);

    /**
     * ③ 工作流待办积压：{@code flow_task} 本身即「运行中任务」表，故直接全表计数。
     *
     * @return 积压条数与最早待办时间（无行时 {@code backlogOldest} 为 null）
     */
    @Select("""
        select count(*)         as backlog_count,
               min(create_time) as backlog_oldest
        from flow_task
        where del_flag = '0'
        """)
    PlatformDigestRow selectBacklog();

    /**
     * ③ 积压最多的节点名（并列时取节点名最大者，保证结果稳定）。
     *
     * @return 仅填 {@code backlogTopNode} 的行
     */
    @Select("""
        select node_name as backlog_top_node
        from flow_task
        where del_flag = '0' and node_name is not null
        group by node_name
        order by count(*) desc, node_name desc
        limit 1
        """)
    PlatformDigestRow selectBacklogTopNode();

    /**
     * ④ 当日权限决策计数。
     *
     * @param dayStart 当日 00:00
     * @param dayEnd   次日 00:00
     * @return 权限块（无行时三者皆为 null）
     */
    @Select("""
        select sum(case when decision = '0' then 1 else 0 end) as perm_allow_count,
               sum(case when decision = '1' then 1 else 0 end) as perm_ask_count,
               sum(case when decision = '2' then 1 else 0 end) as perm_deny_count
        from ai_permission_decision_log
        where create_time >= #{dayStart} and create_time < #{dayEnd}
        """)
    PlatformDigestRow selectPermission(@Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd);

    /**
     * ⑤ 当日登录成功 / 失败计数。
     *
     * @param dayStart 当日 00:00
     * @param dayEnd   次日 00:00
     * @return 登录块（无行时 {@code loginFailCount} 为 null）
     */
    @Select("""
        select sum(case when status = '0' then 1 else 0 end) as login_success_count,
               sum(case when status = '1' then 1 else 0 end) as login_fail_count
        from sys_login_info
        where login_time >= #{dayStart} and login_time < #{dayEnd}
        """)
    PlatformDigestRow selectLogin(@Param("dayStart") LocalDateTime dayStart,
                                  @Param("dayEnd") LocalDateTime dayEnd);

    /**
     * ⑤ 当日「新 IP」数：当日登录来源 IP 中，在过去 {@code windowStart ~ dayStart} 窗口内从未出现过的个数。
     *
     * <p>口径说明：这是「异地 / 新 IP」的**近似判据**（不依赖 IP 归属地库），
     * 目的是给值班人一个可核对的异动信号，而不是安全判定结论。</p>
     *
     * @param dayStart    当日 00:00
     * @param dayEnd      次日 00:00
     * @param windowStart 观察窗口起点（当日 − 观察天数）
     * @return 仅填 {@code loginAnomalyCount} 的行
     */
    @Select("""
        select count(distinct l.ipaddr) as login_anomaly_count
        from sys_login_info l
        where l.login_time >= #{dayStart} and l.login_time < #{dayEnd}
          and l.ipaddr is not null and l.ipaddr <> ''
          and not exists (select 1 from sys_login_info p
                          where p.ipaddr = l.ipaddr
                            and p.login_time >= #{windowStart} and p.login_time < #{dayStart})
        """)
    PlatformDigestRow selectNewIpCount(@Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd,
                                       @Param("windowStart") LocalDateTime windowStart);

    /**
     * 入口智能体（小Z，{@code singleton = '1'}）的日 Token 配额。
     *
     * @return 配额值；{@code null} 或 {@code 0} 均表示**不限**（与 {@code AiQuotaServiceImpl} 的 UNLIMITED 口径一致）
     */
    @Select("""
        select daily_token_quota
        from ai_entry_agent
        where del_flag = '0' and singleton = '1'
        """)
    Long selectEntryDailyTokenQuota();

    /**
     * 权限决策明细（供「下钻」追问，如「昨天有哪些被拒绝的调用」）。
     *
     * @param dayStart 当日 00:00
     * @param dayEnd   次日 00:00
     * @param limit    条数上限（调用方已做区间收敛）
     * @return 明细行（列名即键，下划线形式）
     */
    @Select("""
        select decision, decision_source, tool_code, tool_name, user_id, confirmed, confirm_source, create_time
        from ai_permission_decision_log
        where create_time >= #{dayStart} and create_time < #{dayEnd}
        order by create_time desc
        limit #{limit}
        """)
    List<Map<String, Object>> selectPermissionDetail(@Param("dayStart") LocalDateTime dayStart,
                                                    @Param("dayEnd") LocalDateTime dayEnd,
                                                    @Param("limit") int limit);

    /**
     * 登录明细（供「昨天登录失败都是哪些 IP」这类追问）。
     *
     * @param dayStart 当日 00:00
     * @param dayEnd   次日 00:00
     * @param limit    条数上限
     * @return 明细行
     */
    @Select("""
        select user_name, ipaddr, login_location, status, msg, login_time
        from sys_login_info
        where login_time >= #{dayStart} and login_time < #{dayEnd}
        order by login_time desc
        limit #{limit}
        """)
    List<Map<String, Object>> selectLoginDetail(@Param("dayStart") LocalDateTime dayStart,
                                               @Param("dayEnd") LocalDateTime dayEnd,
                                               @Param("limit") int limit);

    /**
     * 定时任务**失败**明细。
     *
     * @param dayStart 当日 00:00
     * @param dayEnd   次日 00:00
     * @param limit    条数上限
     * @return 明细行
     */
    @Select("""
        select task_id, task_name, status, duration_ms, total_tokens, tool_call_count, error_msg, create_time
        from ai_agent_task_run
        where create_time >= #{dayStart} and create_time < #{dayEnd} and status = '1'
        order by create_time desc
        limit #{limit}
        """)
    List<Map<String, Object>> selectTaskFailDetail(@Param("dayStart") LocalDateTime dayStart,
                                                   @Param("dayEnd") LocalDateTime dayEnd,
                                                   @Param("limit") int limit);

    /**
     * 当日各智能体用量（按 Token 降序）。
     *
     * @param statDate 统计日期
     * @param limit    条数上限
     * @return 明细行（agent_id / agent_code / agent_name / total_tokens / request_count / tool_call_count）
     */
    @Select("""
        select s.agent_id,
               coalesce(e.agent_code, x.agent_code) as agent_code,
               coalesce(e.agent_name, x.agent_name) as agent_name,
               sum(s.total_tokens)                  as total_tokens,
               sum(s.request_count)                 as request_count,
               sum(s.tool_call_count)               as tool_call_count
        from ai_usage_stat s
        left join ai_entry_agent e on e.id = s.agent_id
        left join ai_expert_agent x on x.id = s.agent_id
        where s.stat_date = #{statDate}
        group by s.agent_id, coalesce(e.agent_code, x.agent_code), coalesce(e.agent_name, x.agent_name)
        order by sum(s.total_tokens) desc
        limit #{limit}
        """)
    List<Map<String, Object>> selectUsageByAgent(@Param("statDate") LocalDate statDate,
                                                 @Param("limit") int limit);
}
