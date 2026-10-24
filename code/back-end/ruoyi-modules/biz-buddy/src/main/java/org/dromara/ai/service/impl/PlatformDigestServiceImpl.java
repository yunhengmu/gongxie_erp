package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.vo.PlatformDigestRow;
import org.dromara.ai.domain.vo.PlatformDigestVo;
import org.dromara.ai.mapper.PlatformDigestMapper;
import org.dromara.ai.service.IPlatformDigestService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台运行日报统计服务实现（只读）。
 *
 * <p>五块口径与「无数据处理」见 {@link PlatformDigestVo} 与 [docs/23]：
 * ①用量与配额（环比 / 使用率在 Java 侧算）、②定时任务成败、③工作流待办积压（按节点，不按人）、
 * ④权限决策计数、⑤登录与「新 IP」异动。**配额 0 视为不限 → 使用率为 null**。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformDigestServiceImpl implements IPlatformDigestService {

    private final PlatformDigestMapper digestMapper;

    @Override
    public PlatformDigestVo digest(LocalDate date) {
        LocalDate statDate = date == null ? LocalDate.now().minusDays(1) : date;
        LocalDateTime dayStart = statDate.atStartOfDay();
        LocalDateTime dayEnd = statDate.plusDays(1).atStartOfDay();

        PlatformDigestVo vo = new PlatformDigestVo();
        vo.setDigestDate(statDate);
        vo.setUsage(buildUsage(statDate));
        vo.setTask(buildTask(dayStart, dayEnd));
        vo.setBacklog(buildBacklog());
        vo.setPermission(buildPermission(dayStart, dayEnd));
        vo.setLogin(buildLogin(statDate, dayStart, dayEnd));
        return vo;
    }

    /** ① 用量与配额：无用量记录时整块为 null；环比缺上日数据、配额为 0（不限）时对应字段为 null。 */
    private PlatformDigestVo.Usage buildUsage(LocalDate statDate) {
        PlatformDigestRow today = digestMapper.selectUsage(statDate);
        if (today == null || today.getUsageTotalTokens() == null) {
            return null;
        }
        PlatformDigestVo.Usage usage = new PlatformDigestVo.Usage();
        usage.setTotalTokens(today.getUsageTotalTokens());
        usage.setRequestCount(nullSafe(today.getUsageRequestCount()));
        usage.setToolCallCount(nullSafe(today.getUsageToolCallCount()));
        usage.setCost(today.getUsageCost());
        usage.setComparePct(comparePct(today.getUsageTotalTokens(), previousTokens(statDate)));
        usage.setQuotaUsagePct(quotaUsagePct(today.getUsageTotalTokens()));
        return usage;
    }

    /** 上日 Token 合计；无上日数据时返回 null。 */
    private Long previousTokens(LocalDate statDate) {
        PlatformDigestRow prev = digestMapper.selectUsage(statDate.minusDays(1));
        return prev == null ? null : prev.getUsageTotalTokens();
    }

    /** Token 环比（%）：上日无数据或为 0 时返回 null。 */
    private BigDecimal comparePct(Long today, Long previous) {
        if (previous == null || previous == 0L || today == null) {
            return null;
        }
        return BigDecimal.valueOf(today - previous)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(previous), 2, RoundingMode.HALF_UP);
    }

    /** 配额使用率（%）：配额为 null 或 0（不限）时返回 null。 */
    private BigDecimal quotaUsagePct(Long totalTokens) {
        Long quota = digestMapper.selectEntryDailyTokenQuota();
        if (quota == null || quota <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(totalTokens)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(quota), 2, RoundingMode.HALF_UP);
    }

    /** ② 定时任务成败：探针为 failCount（无行时为 null）。 */
    private PlatformDigestVo.Task buildTask(LocalDateTime dayStart, LocalDateTime dayEnd) {
        PlatformDigestRow row = digestMapper.selectTaskRun(dayStart, dayEnd);
        if (row == null || row.getTaskFailCount() == null) {
            return null;
        }
        PlatformDigestVo.Task task = new PlatformDigestVo.Task();
        task.setRunCount(nullSafe(row.getTaskRunCount()));
        task.setFailCount(row.getTaskFailCount());
        return task;
    }

    /** ③ 待办积压：{@code flow_task} 即「运行中任务」表；无待办时 count = 0（有意义的 0，不算无数据）。 */
    private PlatformDigestVo.Backlog buildBacklog() {
        PlatformDigestRow row = digestMapper.selectBacklog();
        if (row == null || row.getBacklogOldest() == null) {
            return null;
        }
        PlatformDigestVo.Backlog backlog = new PlatformDigestVo.Backlog();
        backlog.setCount(nullSafe(row.getBacklogCount()));
        backlog.setTopNode(digestMapper.selectBacklogTopNode() == null
            ? null : digestMapper.selectBacklogTopNode().getBacklogTopNode());
        backlog.setMaxWaitMinutes(Duration.between(row.getBacklogOldest(), LocalDateTime.now()).toMinutes());
        return backlog;
    }

    /** ④ 权限决策计数：同一查询三者同生同灭，任一为 null 即视为无数据。 */
    private PlatformDigestVo.Permission buildPermission(LocalDateTime dayStart, LocalDateTime dayEnd) {
        PlatformDigestRow row = digestMapper.selectPermission(dayStart, dayEnd);
        if (row == null || (row.getPermAllowCount() == null && row.getPermAskCount() == null
            && row.getPermDenyCount() == null)) {
            return null;
        }
        PlatformDigestVo.Permission permission = new PlatformDigestVo.Permission();
        permission.setAllowCount(nullSafe(row.getPermAllowCount()));
        permission.setAskCount(nullSafe(row.getPermAskCount()));
        permission.setDenyCount(nullSafe(row.getPermDenyCount()));
        return permission;
    }

    /** ⑤ 登录与异动：探针为 failCount（无登录记录时为 null）。 */
    private PlatformDigestVo.Login buildLogin(LocalDate statDate, LocalDateTime dayStart, LocalDateTime dayEnd) {
        PlatformDigestRow row = digestMapper.selectLogin(dayStart, dayEnd);
        if (row == null || row.getLoginFailCount() == null) {
            return null;
        }
        PlatformDigestVo.Login login = new PlatformDigestVo.Login();
        login.setSuccessCount(nullSafe(row.getLoginSuccessCount()));
        login.setFailCount(row.getLoginFailCount());
        PlatformDigestRow newIp = digestMapper.selectNewIpCount(dayStart, dayEnd,
            dayStart.minusDays(NEW_IP_WINDOW_DAYS));
        login.setAnomalyCount(newIp == null ? null : newIp.getLoginAnomalyCount());
        return login;
    }

    @Override
    public List<Map<String, Object>> drill(String block, LocalDate date, int limit) {
        int rows = normalizeLimit(limit);
        LocalDate statDate = date == null ? LocalDate.now().minusDays(1) : date;
        LocalDateTime dayStart = statDate.atStartOfDay();
        LocalDateTime dayEnd = statDate.plusDays(1).atStartOfDay();
        String normalized = block == null ? "" : block.trim().toLowerCase();
        return switch (normalized) {
            case BLOCK_PERMISSION -> digestMapper.selectPermissionDetail(dayStart, dayEnd, rows);
            case BLOCK_LOGIN -> digestMapper.selectLoginDetail(dayStart, dayEnd, rows);
            case BLOCK_TASK -> digestMapper.selectTaskFailDetail(dayStart, dayEnd, rows);
            case BLOCK_USAGE -> usageByAgent(statDate, rows);
            default -> List.of();
        };
    }

    @Override
    public List<Map<String, Object>> usageByAgent(LocalDate date, int limit) {
        LocalDate statDate = date == null ? LocalDate.now().minusDays(1) : date;
        return digestMapper.selectUsageByAgent(statDate, normalizeLimit(limit));
    }

    /** 条数收敛：非正数取默认值，超过上限则截到上限。 */
    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_DRILL_LIMIT;
        }
        return Math.min(limit, MAX_DRILL_LIMIT);
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
