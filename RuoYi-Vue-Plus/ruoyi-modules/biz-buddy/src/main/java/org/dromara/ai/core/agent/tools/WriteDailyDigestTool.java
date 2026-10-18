package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.digest.DailyDigestPublisher;
import org.dromara.ai.domain.AiDailyDigest;
import org.dromara.common.core.utils.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写入平台运行日报工具（写入类）。
 *
 * <p><b>声明式产出</b>：模型只提供**日期**与**日报正文**；五块数值由 {@link DailyDigestPublisher}
 * 现取（与只读工具同一口径），模型**不传、也改不了**数字。落库按 {@code digest_date} upsert，
 * 同日重调只更新不新增。</p>
 *
 * <p>权限沿用框架装配期写入的三档（{@code ai_agent_tool.decision}）：定时任务/无人值守链路须配
 * 「允许」，否则会被 DONT_ASK 与「需确认改写为拒绝」拦下。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class WriteDailyDigestTool extends ToolBase {

    /**
     * 工具名称
     */
    public static final String TOOL_NAME = "write_daily_digest";

    /**
     * 工具描述
     */
    private static final String DESCRIPTION = "把某一天的平台运行日报写入平台（落库，按日期覆盖更新）。"
        + "传入日报正文与日期即可：五块数值（用量 / 任务 / 待办 / 权限 / 登录）由平台按该日期现取，"
        + "**不要自行编造或换算数字**。正文首行写明「结论级别：平稳 / 需关注 / 需处理」。"
        + "不传 date 表示昨天。同日重复调用只更新，不会新增行。";

    private final DailyDigestPublisher publisher;

    /**
     * @param publisher 日报落库服务（取数 + upsert）
     */
    public WriteDailyDigestTool(DailyDigestPublisher publisher) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(false)
            .concurrencySafe(false));
        this.publisher = publisher;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? TOOL_NAME : use.getName();
        Map<String, Object> input = param == null || param.getInput() == null ? Map.of() : param.getInput();
        String rawDate = StringUtils.trim(stringValue(input.get("date")));
        LocalDate date = null;
        if (StringUtils.isNotBlank(rawDate)) {
            try {
                date = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return Mono.just(ToolResultBlock.error("date 需为 YYYY-MM-DD 格式：" + rawDate).withIdAndName(id, name));
            }
        }
        String text = StringUtils.trim(stringValue(input.get("digest_text")));
        if (StringUtils.isBlank(text)) {
            return Mono.just(ToolResultBlock.error("digest_text（日报正文）不能为空").withIdAndName(id, name));
        }
        Long sessionId = parseId(param == null || param.getRuntimeContext() == null
            ? null : param.getRuntimeContext().getSessionId());
        AiDailyDigest saved = publisher.publish(date, text, sessionId);
        if (saved == null) {
            return Mono.just(ToolResultBlock.error("运行日报落库失败，请检查平台日志").withIdAndName(id, name));
        }
        return Mono.just(ToolResultBlock.text("运行日报已落库（日期：" + saved.getDigestDate()
            + "，级别：" + levelLabel(saved.getLevel()) + "）").withIdAndName(id, name));
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("date", Map.of("type", "string", "description", "日报日期（YYYY-MM-DD）；不传则取昨天"));
        properties.put("digest_text", Map.of("type", "string",
            "description", "日报正文（首行写明「结论级别：平稳 / 需关注 / 需处理」）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("digest_text"));
        return schema;
    }

    /**
     * 级别文案。
     *
     * @param level 级别码（0/1/2 或 null）
     * @return 文案
     */
    private static String levelLabel(String level) {
        if (AiDailyDigest.LEVEL_STABLE.equals(level)) {
            return "平稳";
        }
        if (AiDailyDigest.LEVEL_WATCH.equals(level)) {
            return "需关注";
        }
        if (AiDailyDigest.LEVEL_ACTION.equals(level)) {
            return "需处理";
        }
        return "未定级";
    }

    /**
     * 解析数字 ID。
     *
     * @param raw 原始字符串
     * @return 数字 ID；非法时返回 null
     */
    private Long parseId(String raw) {
        String value = StringUtils.trim(raw);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全转字符串。
     *
     * @param value 原始值
     * @return 字符串（null 转空串）
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
