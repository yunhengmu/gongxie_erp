package org.dromara.ai.core.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.channel.AiChannelRuntime;
import org.dromara.ai.core.channel.AiChannelRuntimeRegistry;
import org.dromara.ai.domain.AiAgentTask;
import org.dromara.ai.domain.AiDailyDigest;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.vo.PlatformDigestVo;
import org.dromara.ai.mapper.AiDailyDigestMapper;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IPlatformDigestService;
import org.dromara.common.core.enums.PushSourceEnum;
import org.dromara.common.core.enums.PushTypeEnum;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.system.api.MessageService;
import org.dromara.system.api.domain.PushPayloadDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务产出投递器（定时任务 × 日报骨架）。
 *
 * <p>定时任务执行成功后，若其 {@code ai_agent_task.output_config} 配了投递方式，则由本类完成
 * 「落库 → 站内推送 → 渠道推送」：</p>
 * <ol>
 *   <li><b>落库</b>：按 {@code ai_daily_digest.digest_date}（昨日）**upsert** —— 同日重跑只更新不新增。
 *       五块快照取 {@link IPlatformDigestService}（与工具同一口径），正文取本次助手消息；</li>
 *   <li><b>结论级别</b>：从模型正文**全文匹配**「结论级别：X」（模型可能输出前言）→ 0/1/2，
 *       解析失败记 {@code null} 并把该行备注标为降级；</li>
 *   <li><b>站内推送</b>：{@link MessageService#publishMessage} 给配置目标用户；</li>
 *   <li><b>渠道推送</b>：复用渠道出站 {@link AiChannelRuntime#send}，目标取自配置。</li>
 * </ol>
 *
 * <p><b>失败隔离</b>：落库先行、推送随后，**推送失败不阻断落库**，只告警并保留正文以便补投。
 * 同一「任务 + 计划时间」的推送以 Redis 键去重；推送未全部成功时不置键，故重跑可补投。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOutputDispatcher {

    /**
     * 推送去重键前缀
     */
    private static final String KEY_PREFIX = "bizbuddy:ai:task-output:";

    /**
     * 推送去重键保留时长（跨天仍需支撑同日补投）
     */
    private static final Duration DEDUP_TTL = Duration.ofDays(2);

    /**
     * 渠道推送等待上限
     */
    private static final Duration CHANNEL_SEND_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 计划时间的去重键格式
     */
    private static final DateTimeFormatter SCHEDULED_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 结论级别解析（全文匹配，容忍模型在级别行前输出前言）
     */
    private static final Pattern LEVEL_PATTERN =
        Pattern.compile("结论级别\\s*[:：]\\s*(平稳|需关注|需处理)");

    private static final String LEVEL_STABLE_TEXT = "平稳";
    private static final String LEVEL_WATCH_TEXT = "需关注";
    private static final String LEVEL_ACTION_TEXT = "需处理";

    /**
     * 平稳日正文结尾的一句话（§9.3）
     */
    private static final String CALM_SENTENCE = "运行平稳";

    /**
     * 结论级别解析失败时写入日报备注的说明
     */
    private static final String DEGRADED_REMARK = "结论级别解析失败：模型正文未含「结论级别：X」，level 记为 null";

    private final IPlatformDigestService digestService;

    private final AiDailyDigestMapper digestMapper;

    private final AiMessageMapper messageMapper;

    private final MessageService messageService;

    private final AiChannelRuntimeRegistry channelRuntimeRegistry;

    /**
     * 投递一次任务产出（落库 + 站内推送 + 渠道推送）。
     *
     * <p>未配置 {@code output_config} 时直接返回（不影响既有任务行为）。本方法**不抛出异常**：
     * 投递是任务执行后的附加动作，任何失败只告警，不回写任务状态。</p>
     *
     * @param task          任务实体（携带 output_config）
     * @param runId         本次执行记录主键（落库溯源）
     * @param sessionId     本次执行会话主键（落库溯源）
     * @param messageId     本次执行助手消息主键（取正文）
     * @param scheduledTime 本次计划执行时间（推送去重键；手动触发为 null）
     */
    public void dispatch(AiAgentTask task, Long runId, Long sessionId, Long messageId,
                         LocalDateTime scheduledTime) {
        if (task == null || StringUtils.isBlank(task.getOutputConfig())) {
            return;
        }
        try {
            TaskOutputConfig config = JsonUtils.parseObject(task.getOutputConfig(), TaskOutputConfig.class);
            if (config == null || !config.hasAnyTarget()) {
                log.info("任务未配置可用的投递目标，跳过投递：taskId={}", task.getId());
                return;
            }
            LocalDate digestDate = LocalDate.now().minusDays(1);
            String raw = loadAssistantText(messageId);
            String level = parseLevel(raw);
            PlatformDigestVo vo = digestService.digest(digestDate);
            // 正文压缩（§9.3）：平稳日由投递器按五块快照生成「5 行 + 一句运行平稳」；
            // 其余情况保真模型正文，并去掉级别行之前的前言（模型偶发开场白）
            String digestText = AiDailyDigest.LEVEL_STABLE.equals(level) ? compactText(vo) : stripPreamble(raw);
            upsertDigest(vo, digestDate, level, digestText, runId, sessionId);
            // 落库已成，推送失败不阻断：仅告警；正文已落库，重跑可补投
            String dedupKey = dedupKey(task.getId(), digestDate, scheduledTime);
            if (isDelivered(dedupKey)) {
                log.info("本次产出的推送已完成，跳过重复推送：taskId={}, key={}", task.getId(), dedupKey);
                return;
            }
            boolean delivered = pushStation(config, digestText, digestDate, level, runId, sessionId)
                & pushChannel(config.getChannel(), digestText);
            if (delivered) {
                markDelivered(dedupKey);
            } else {
                log.warn("任务产出推送未全部成功，保留补投机会：taskId={}, key={}", task.getId(), dedupKey);
            }
        } catch (Exception e) {
            log.error("任务产出投递异常（不影响任务执行状态）：taskId={}, runId={}", task.getId(), runId, e);
        }
    }

    /**
     * 按日期 upsert 日报行（同日重跑只更新不新增）。
     */
    private void upsertDigest(PlatformDigestVo vo, LocalDate digestDate, String level, String text,
                              Long runId, Long sessionId) {
        LambdaQueryWrapper<AiDailyDigest> lqw = Wrappers.<AiDailyDigest>lambdaQuery()
            .eq(AiDailyDigest::getDigestDate, digestDate);
        AiDailyDigest existing = digestMapper.selectOne(lqw);
        AiDailyDigest entity = buildDigest(vo, digestDate, level, text, runId, sessionId);
        if (existing == null) {
            entity.setId(IdGeneratorUtil.nextLongId());
            digestMapper.insert(entity);
            log.info("日报已落库（新增）：date={}, level={}, runId={}", digestDate, level, runId);
        } else {
            entity.setId(existing.getId());
            digestMapper.updateById(entity);
            log.info("日报已落库（更新）：date={}, level={}, runId={}", digestDate, level, runId);
        }
    }

    /**
     * 组装日报行：五块为空处按列默认值置 0（有数据的块取原值）。
     */
    private AiDailyDigest buildDigest(PlatformDigestVo vo, LocalDate digestDate, String level, String text,
                                      Long runId, Long sessionId) {
        AiDailyDigest entity = new AiDailyDigest();
        entity.setDigestDate(digestDate);
        entity.setLevel(level);
        entity.setDigestText(text);
        entity.setTaskRunId(runId);
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
     * @param text 模型正文
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
        String word = matcher.group(1);
        return switch (word) {
            case LEVEL_STABLE_TEXT -> AiDailyDigest.LEVEL_STABLE;
            case LEVEL_WATCH_TEXT -> AiDailyDigest.LEVEL_WATCH;
            case LEVEL_ACTION_TEXT -> AiDailyDigest.LEVEL_ACTION;
            default -> null;
        };
    }

    /**
     * 去掉级别之前的前言（模型偶发开场白，可能独占一行也可能与级别同行）；无级别行时原样返回。
     *
     * @param text 模型正文
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
     * 平稳日正文压缩：按五块快照生成「5 行 + 一句运行平稳」（§9.3）；无数据的块如实写「无数据」。
     *
     * @param vo 五块汇总
     * @return 压缩后的日报正文
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
        String text = value.toPlainString();
        return (value.signum() >= 0 ? "+" : "") + text + "%";
    }

    /**
     * 站内推送（{@link MessageService#publishMessage}）；未配置目标视为已满足。
     *
     * @return 是否成功（未配置时为 true）
     */
    private boolean pushStation(TaskOutputConfig config, String text, LocalDate digestDate, String level,
                                Long runId, Long sessionId) {
        List<Long> userIds = config.getStationUserIds();
        if (userIds == null || userIds.isEmpty() || StringUtils.isBlank(text)) {
            return true;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("digestDate", digestDate.toString());
            data.put("level", level);
            data.put("taskRunId", runId);
            data.put("sessionId", sessionId);
            messageService.publishMessage(userIds, PushPayloadDTO.of(
                PushTypeEnum.MESSAGE, PushSourceEnum.BACKEND, text, data));
            log.info("日报站内推送完成：users={}, date={}", userIds, digestDate);
            return true;
        } catch (Exception e) {
            log.warn("日报站内推送失败（不影响落库）：users={}, 原因={}", userIds, e.getMessage());
            return false;
        }
    }

    /**
     * 渠道推送（复用渠道出站构件）；未配置渠道视为已满足。
     *
     * @return 是否成功（未配置时为 true）
     */
    private boolean pushChannel(TaskOutputConfig.ChannelTarget target, String text) {
        if (target == null || StringUtils.isBlank(target.getChannelCode())
            || StringUtils.isBlank(target.getTargetKey()) || StringUtils.isBlank(text)) {
            return true;
        }
        try {
            AiChannelRuntime runtime = channelRuntimeRegistry.resolve(target.getChannelCode());
            if (runtime == null) {
                log.warn("渠道推送失败：渠道不存在 / 已停用 / 装配失败，channelCode={}", target.getChannelCode());
                return false;
            }
            Peer peer = toPeer(target.getTargetType(), target.getTargetKey());
            // 出站地址格式与框架 ChannelRouter 一致：channelCode:peerKind:peerId（见渠道入站链路）
            String to = runtime.getChannelCode() + ":" + peer.kind().name() + ":" + peer.id();
            String threadId = peer.kind().isThread() ? peer.id() : null;
            OutboundAddress address = new OutboundAddress(runtime.getChannelCode(),
                runtime.getChannelCode(), to, threadId);
            runtime.send(address,
                    List.of(Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build()))
                .block(CHANNEL_SEND_TIMEOUT);
            log.info("日报渠道推送完成：channelCode={}, to={}", target.getChannelCode(), to);
            return true;
        } catch (Exception e) {
            log.warn("日报渠道推送失败（不影响落库）：channelCode={}, 原因={}",
                target.getChannelCode(), e.getMessage());
            return false;
        }
    }

    /**
     * 目标类型 → 框架 peer（direct / group / thread，缺省按 direct）。
     */
    private Peer toPeer(String targetType, String targetKey) {
        String type = StringUtils.trim(targetType);
        if ("group".equalsIgnoreCase(type)) {
            return Peer.group(targetKey);
        }
        if ("thread".equalsIgnoreCase(type)) {
            return Peer.thread(targetKey);
        }
        return Peer.direct(targetKey);
    }

    /**
     * 取本次执行的助手消息正文。
     */
    private String loadAssistantText(Long messageId) {
        if (messageId == null) {
            return null;
        }
        AiMessage message = messageMapper.selectById(messageId);
        return message == null ? null : message.getContent();
    }

    /**
     * 推送去重键：{@code 任务 + 计划时间}（手动触发按日报日期）。
     */
    private String dedupKey(Long taskId, LocalDate digestDate, LocalDateTime scheduledTime) {
        String scope = scheduledTime == null
            ? "manual-" + digestDate : scheduledTime.format(SCHEDULED_FORMAT);
        return KEY_PREFIX + taskId + ":" + scope;
    }

    private boolean isDelivered(String key) {
        try {
            return RedisUtils.getCacheObject(key) != null;
        } catch (Exception e) {
            log.warn("推送去重查询失败（按未推送处理）：key={}, 原因={}", key, e.getMessage());
            return false;
        }
    }

    private void markDelivered(String key) {
        try {
            RedisUtils.setCacheObject(key, "1", DEDUP_TTL);
        } catch (Exception e) {
            log.warn("推送去重登记失败：key={}, 原因={}", key, e.getMessage());
        }
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static int intOf(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

    /**
     * 任务产出投递配置（{@code ai_agent_task.output_config} 的 JSON 结构）。
     *
     * <pre>
     * {
     *   "stationUserIds": [1761100000000000001],
     *   "channel": { "channelCode": "ops_dingtalk", "targetType": "direct", "targetKey": "open_id" }
     * }
     * </pre>
     *
     * <p>{@code stationUserIds} 为站内推送目标用户；{@code channel} 为渠道推送目标
     * （{@code targetType} ∈ direct / group / thread，{@code targetKey} 为外部用户标识或线程标识）。
     * 两者可任缺，缺项即不做该种投递。</p>
     */
    @Data
    public static class TaskOutputConfig {

        /**
         * 站内推送目标用户（空表示不推站内）
         */
        private List<Long> stationUserIds;

        /**
         * 渠道推送目标（空表示不推渠道）
         */
        private ChannelTarget channel;

        /**
         * 是否配置了任一投递目标。
         */
        public boolean hasAnyTarget() {
            return (stationUserIds != null && !stationUserIds.isEmpty()) || channel != null;
        }

        /**
         * 渠道推送目标。
         */
        @Data
        public static class ChannelTarget {

            /**
             * 渠道编码（ai_channel.channel_code）
             */
            private String channelCode;

            /**
             * 目标类型（direct 单聊 / group 群 / thread 线程；缺省 direct）
             */
            private String targetType;

            /**
             * 目标标识（单聊/群为外部用户或群标识；线程为 thread_key）
             */
            private String targetKey;
        }
    }

}
