package org.dromara.ai.service;

import org.dromara.ai.domain.vo.PlatformDigestVo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 平台运行日报统计服务。
 *
 * <p><b>唯一口径</b>：五块统计只在本服务实现一处，日报工具（给模型看）与任务产出投递器
 * （落库 / 推送）都调用它，避免「工具算一遍、落库再算一遍」的口径漂移。</p>
 *
 * @author BizBuddy
 */
public interface IPlatformDigestService {

    /** 「新 IP」观察窗口天数（当日之前回看的天数）。 */
    int NEW_IP_WINDOW_DAYS = 30;

    /** 下钻块：权限决策明细。 */
    String BLOCK_PERMISSION = "permission";
    /** 下钻块：登录明细。 */
    String BLOCK_LOGIN = "login";
    /** 下钻块：定时任务失败明细。 */
    String BLOCK_TASK = "task";
    /** 下钻块：当日各智能体用量。 */
    String BLOCK_USAGE = "usage";

    /** 下钻默认条数。 */
    int DEFAULT_DRILL_LIMIT = 20;
    /** 下钻最大条数。 */
    int MAX_DRILL_LIMIT = 100;

    /**
     * 取指定日期的五块汇总；块为 {@code null} 表示当日该块无数据。
     *
     * @param date 统计日期；{@code null} 时取**昨天**
     * @return 日报汇总（不含结论级别 —— 级别由技能阈值判定）
     */
    PlatformDigestVo digest(LocalDate date);

    /**
     * 按块下钻明细（供追问，如「昨天登录失败都是哪些 IP」）。
     *
     * @param block 块名，取值见 {@link #BLOCK_PERMISSION} / {@link #BLOCK_LOGIN} / {@link #BLOCK_TASK} / {@link #BLOCK_USAGE}
     * @param date  统计日期；{@code null} 时取**昨天**
     * @param limit 条数上限；非正数取默认值，超过上限则收敛到上限
     * @return 明细行（列名即键）；未知块返回空列表
     */
    List<Map<String, Object>> drill(String block, LocalDate date, int limit);

    /**
     * 当日各智能体用量（按 Token 降序）。
     *
     * @param date  统计日期；{@code null} 时取**昨天**
     * @param limit 条数上限；非正数取默认值，超过上限则收敛到上限
     * @return 明细行（agent_id / agent_code / agent_name / total_tokens / request_count / tool_call_count）
     */
    List<Map<String, Object>> usageByAgent(LocalDate date, int limit);
}
