package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 平台运行日报**五块汇总**（服务端唯一口径 —— 工具与投递器共用同一份结果）。
 *
 * <p>块为 {@code null} 表示**当日该块无数据**，而不是 0；日报正文与落库字段据此区分
 * 「无数据」与「有数据且为 0」。</p>
 *
 * <p>本类**不含结论级别**：级别（平稳 / 需关注 / 需处理）由**技能**（提示词里的阈值口径）
 * 判定，阈值只保留在技能一处，避免服务端与技能两处阈值漂移；投递器落库时解析模型正文
 * 首行的级别，解析失败则记 {@code null} 并把该次任务标为降级。</p>
 *
 * @author BizBuddy
 */
@Data
public class PlatformDigestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 统计日期 */
    private LocalDate digestDate;

    /** ① 用量与配额；null 表示当日无用量记录 */
    private Usage usage;

    /** ② 定时任务成败；null 表示当日无任务执行记录 */
    private Task task;

    /** ③ 工作流待办积压（按节点聚合，不按人）；null 表示无可判定数据 */
    private Backlog backlog;

    /** ④ 权限决策；null 表示当日无决策记录 */
    private Permission permission;

    /** ⑤ 登录与异动；null 表示当日无登录记录 */
    private Login login;

    /** ① 用量与配额 */
    @Data
    public static class Usage implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 当日总 Token */
        private Long totalTokens;
        /** 当日请求（对话轮次）数 */
        private Long requestCount;
        /** 当日工具调用次数 */
        private Long toolCallCount;
        /** 当日费用估算（元） */
        private BigDecimal cost;
        /** Token 环比（%）；null 表示无上日数据 */
        private BigDecimal comparePct;
        /** 配额使用率（%）；null 表示配额为 0（不限）或无配额配置 */
        private BigDecimal quotaUsagePct;
    }

    /** ② 定时任务成败 */
    @Data
    public static class Task implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 执行次数 */
        private Long runCount;
        /** 失败次数 */
        private Long failCount;
    }

    /** ③ 工作流待办积压 */
    @Data
    public static class Backlog implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 运行中（{@code flow_status = waiting}）的待办条数 */
        private Long count;
        /** 积压最多的节点名 */
        private String topNode;
        /** 最久滞留分钟数（由最早待办时间推算） */
        private Long maxWaitMinutes;
    }

    /** ④ 权限决策 */
    @Data
    public static class Permission implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 允许（decision=0）次数 */
        private Long allowCount;
        /** 需确认（decision=1）次数 */
        private Long askCount;
        /** 拒绝（decision=2）次数 */
        private Long denyCount;
    }

    /** ⑤ 登录与异动 */
    @Data
    public static class Login implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 登录成功次数 */
        private Long successCount;
        /** 登录失败次数 */
        private Long failCount;
        /** 当日新 IP 数（近 30 天内未出现过的来源 IP） */
        private Long anomalyCount;
    }
}
