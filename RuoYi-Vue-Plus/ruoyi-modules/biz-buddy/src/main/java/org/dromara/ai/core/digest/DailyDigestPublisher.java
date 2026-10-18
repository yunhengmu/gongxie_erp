package org.dromara.ai.core.digest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiDailyDigest;
import org.dromara.ai.domain.vo.PlatformDigestVo;
import org.dromara.ai.mapper.AiDailyDigestMapper;
import org.dromara.ai.service.IPlatformDigestService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台运行日报落库服务（**只落库**，不做通知/推送）。
 *
 * <p><b>声明式产出</b>：调用方（写工具 {@code write_daily_digest}）只提供**日期**与**日报正文**；
 * <b>五块数值一律由本服务现取</b>（{@link IPlatformDigestService}，与只读工具同一口径），
 * 模型无法传入或改写数字（保住「数字可核对」）。</p>
 *
 * <p><b>级别与正文</b>：从正文**全文匹配**「结论级别：X」得到 0/1/2，解析失败记 {@code null}
 * 并把备注标为降级；平稳日按五块快照压成「5 行 + 一句运行平稳」，其余情况保真模型正文并去掉级别行之前的前言。</p>
 *
 * <p><b>幂等</b>：按 {@code digest_date} **upsert** —— 同日重跑/重调只更新不新增。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyDigestPublisher {

    /**
     * 结论级别解析（全文匹配，容忍模型在级别行前输出前言）
     */
    private static final Pattern LEVEL_PATTERN =
        Pattern.compile("结论级别\\s*[:：]\\s*(平稳|需关注|需处理)");

    private static final String LEVEL_STABLE_TEXT = "平稳";
    private static final String LEVEL_WATCH_TEXT = "需关注";
    private static final String LEVEL_ACTION_TEXT = "需处理";

    /**
     * 平稳日正文结尾的一句话（方案 §9.3）
     */
    private static final String CALM_SENTENCE = "运行平稳";

    /**
     * 结论级别解析失败时写入日报备注的说明
     */
    private static final String DEGRADED_REMARK = "结论级别解析失败：正文未含「结论级别：X」，level 记为 null";

    private final IPlatformDigestService digestService;

    private final AiDailyDigestMapper digestMapper;

    /**
     * 落库某日运行日报（按 {@code date} upsert）。
     *
     * <p>本方法**不抛出异常**：落库是产出动作，失败只告警，由调用方（模型）如实说明。</p>
     *
     * <p><b>溯源</b>：只写 {@code sessionId}；{@code task_run_id} 不在此处猜（执行记录的
     * {@code session_id} 是执行结束后才回填的，写入期解析不到），由**读取侧**按 {@code session_id} 关联。</p>
     *
     * @param date       日报日期；{@code null} 时取**昨天**
     * @param digestText 日报正文（模型产出；可含「结论级别：X」行）
     * @param sessionId  来源会话（可为空）
     * @return 落库后的日报行；失败时返回 {@code null}
     */
    public AiDailyDigest publish(LocalDate date, String digestText, Long sessionId) {
        LocalDate digestDate = date == null ? LocalDate.now().minusDays(1) : date;
        try {
            String level = parseLevel(digestText);
            PlatformDigestVo vo = digestService.digest(digestDate);
            String text = AiDailyDigest.LEVEL_STABLE.equals(level) ? compactText(vo) : stripPreamble(digestText);
            AiDailyDigest entity = buildDigest(vo, digestDate, level, text, sessionId);
            upsert(entity, digestDate);
            log.info("运行日报已落库：date={}, level={}, sessionId={}", digestDate, level, sessionId);
            return entity;
        } catch (Exception e) {
            log.error("运行日报落库失败：date={}, sessionId={}", digestDate, sessionId, e);
            return null;
        }
    }

    /**
     * 按日期 upsert（同日只更新不新增）。
     */
    private void upsert(AiDailyDigest entity, LocalDate digestDate) {
        LambdaQueryWrapper<AiDailyDigest> lqw = Wrappers.<AiDailyDigest>lambdaQuery()
            .eq(AiDailyDigest::getDigestDate, digestDate);
        AiDailyDigest existing = digestMapper.selectOne(lqw);
        if (existing == null) {
            entity.setId(IdGeneratorUtil.nextLongId());
            digestMapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            digestMapper.updateById(entity);
        }
    }

    /**
     * 组装日报行：五块为空处按列默认值置 0（有数据的块取原值）。
     */
    private AiDailyDigest buildDigest(PlatformDigestVo vo, LocalDate digestDate, String level, String text,
                                      Long sessionId) {
        AiDailyDigest entity = new AiDailyDigest();
        entity.setDigestDate(digestDate);
        entity.setLevel(level);
        entity.setDigestText(text);
        entity.setSessionId(sessionId);
        if (level == null) {
            entity.setRemark(DEGRADED_REMARK);
        }
        PlatformDigestVo.Usage usage = vo == null ? null : vo.getUsage();
        entity.setUsageTotalTokens(usage == null ? 0L : nullToZero(usage.getTotalTokens()));
        entity.setUsageRequestCount(usage == null ? 0 : intOf(usage.getRequestCount()));
        entity.setUsageToolCallCount(usage == null ? 0 : intOf(usage.getToolCallCount()));
        entity.setUsageComparePct(usage == null ? null : usage.getComparePct());
        entity.setQuotaUsagePct(usage == null ? null : usage.getQuotaUsagePct());
        PlatformDigestVo.Task task = vo == null ? null : vo.getTask();
        entity.setTaskRunCount(task == null ? 0 : intOf(task.getRunCount()));
        entity.setTaskFailCount(task == null ? 0 : intOf(task.getFailCount()));
        PlatformDigestVo.Backlog backlog = vo == null ? null : vo.getBacklog();
        entity.setBacklogCount(backlog == null ? 0 : intOf(backlog.getCount()));
        entity.setBacklogTopNode(backlog == null ? null : backlog.getTopNode());
        entity.setBacklogMaxWaitMinutes(backlog == null ? null : intOf(backlog.getMaxWaitMinutes()));
        PlatformDigestVo.Permission permission = vo == null ? null : vo.getPermission();
        entity.setPermAllowCount(permission == null ? 0 : intOf(permission.getAllowCount()));
        entity.setPermAskCount(permission == null ? 0 : intOf(permission.getAskCount()));
        entity.setPermDenyCount(permission == null ? 0 : intOf(permission.getDenyCount()));
        PlatformDigestVo.Login login = vo == null ? null : vo.getLogin();
        entity.setLoginSuccessCount(login == null ? 0 : intOf(login.getSuccessCount()));
        entity.setLoginFailCount(login == null ? 0 : intOf(login.getFailCount()));
        entity.setLoginAnomalyCount(login == null ? 0 : intOf(login.getAnomalyCount()));
        return entity;
    }

    /**
     * 全文匹配结论级别文本（平稳 / 需关注 / 需处理）。
     *
     * @param text 日报正文
     * @return 级别码（0/1/2）；未匹配到固定取值时返回 {@code null}
     */
    private String parseLevel(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Matcher matcher = LEVEL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group(1)) {
            case LEVEL_STABLE_TEXT -> AiDailyDigest.LEVEL_STABLE;
            case LEVEL_WATCH_TEXT -> AiDailyDigest.LEVEL_WATCH;
            case LEVEL_ACTION_TEXT -> AiDailyDigest.LEVEL_ACTION;
            default -> null;
        };
    }

    /**
     * 去掉级别之前的前言（模型偶发开场白，可能独占一行也可能与级别同行）；无级别行时原样返回。
     *
     * @param text 日报正文
     * @return 从「结论级别」开始的正文
     */
    private String stripPreamble(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        Matcher matcher = LEVEL_PATTERN.matcher(text);
        return matcher.find() ? text.substring(matcher.start()) : text;
    }

    /**
     * 平稳日正文压缩：按五块快照生成「5 行 + 一句运行平稳」；无数据的块如实写「无数据」。
     */
    private String compactText(PlatformDigestVo vo) {
        return usageLine(vo) + '\n'
            + taskLine(vo) + '\n'
            + backlogLine(vo) + '\n'
            + permissionLine(vo) + '\n'
            + loginLine(vo) + '\n'
            + CALM_SENTENCE;
    }

    private String usageLine(PlatformDigestVo vo) {
        PlatformDigestVo.Usage usage = vo == null ? null : vo.getUsage();
        if (usage == null) {
            return "用量：无数据";
        }
        StringBuilder sb = new StringBuilder("用量：总 Token ").append(nullToZero(usage.getTotalTokens()));
        if (usage.getComparePct() != null) {
            sb.append("（环比 ").append(signedPct(usage.getComparePct())).append('）');
        }
        sb.append("、请求 ").append(intOf(usage.getRequestCount()))
            .append(" 次、工具 ").append(intOf(usage.getToolCallCount())).append(" 次");
        if (usage.getQuotaUsagePct() != null) {
            sb.append("；配额使用率 ").append(signedPct(usage.getQuotaUsagePct()));
        }
        return sb.toString();
    }

    private String taskLine(PlatformDigestVo vo) {
        PlatformDigestVo.Task task = vo == null ? null : vo.getTask();
        if (task == null) {
            return "任务：无数据";
        }
        int run = intOf(task.getRunCount());
        int fail = intOf(task.getFailCount());
        return "任务：执行 " + run + " 次，成功 " + (run - fail) + "，失败 " + fail;
    }

    private String backlogLine(PlatformDigestVo vo) {
        PlatformDigestVo.Backlog backlog = vo == null ? null : vo.getBacklog();
        if (backlog == null) {
            return "待办：无数据";
        }
        String line = "待办：积压 " + intOf(backlog.getCount()) + " 条";
        if (StringUtils.isNotBlank(backlog.getTopNode())) {
            line = line + "（最多节点：" + backlog.getTopNode() + "）";
        }
        return line;
    }

    private String permissionLine(PlatformDigestVo vo) {
        PlatformDigestVo.Permission permission = vo == null ? null : vo.getPermission();
        if (permission == null) {
            return "权限：无数据";
        }
        return "权限：允许 " + intOf(permission.getAllowCount())
            + " / 需确认 " + intOf(permission.getAskCount())
            + " / 拒绝 " + intOf(permission.getDenyCount());
    }

    private String loginLine(PlatformDigestVo vo) {
        PlatformDigestVo.Login login = vo == null ? null : vo.getLogin();
        if (login == null) {
            return "登录：无数据";
        }
        return "登录：成功 " + intOf(login.getSuccessCount())
            + "，失败 " + intOf(login.getFailCount())
            + "；新 IP " + intOf(login.getAnomalyCount()) + " 个";
    }

    /**
     * 百分比文本（带符号，如 {@code +6.01%}）。
     */
    private static String signedPct(BigDecimal value) {
        return (value.signum() >= 0 ? "+" : "") + value.toPlainString() + "%";
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static int intOf(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

}
