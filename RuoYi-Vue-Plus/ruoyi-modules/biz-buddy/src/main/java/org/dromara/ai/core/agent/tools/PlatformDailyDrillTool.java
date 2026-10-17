package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.service.IPlatformDigestService;
import org.dromara.common.json.utils.JsonUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台运行日报 —— **明细下钻**工具（只读）。
 *
 * <p>日报负责「看见」，本工具负责「看清」：回答「昨天登录失败都是哪些 IP」
 * 「昨天有哪些调用被拒绝」这类追问。四种块（permission / login / task / usage）分别对应
 * 权限决策、登录、定时任务失败与按智能体用量明细。</p>
 *
 * @author BizBuddy
 */
public class PlatformDailyDrillTool extends ToolBase {

    /**
     * 工具编码（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "platform_daily_drill";

    /** 合法块名集合（未知块直接回喂错误说明，避免模型猜） */
    private static final Set<String> BLOCKS = Set.of(
        IPlatformDigestService.BLOCK_PERMISSION,
        IPlatformDigestService.BLOCK_LOGIN,
        IPlatformDigestService.BLOCK_TASK,
        IPlatformDigestService.BLOCK_USAGE);

    private static final String DESCRIPTION =
        "下钻某一天（默认昨天）运行日报的明细，回答追问用："
            + "block=permission 权限决策明细（决策 / 工具 / 用户 / 是否确认 / 时间）；"
            + "block=login 登录明细（用户 / IP / 地点 / 状态 / 信息 / 时间）；"
            + "block=task 定时任务失败明细（失败原因 / 耗时 / Token）；"
            + "block=usage 当日各智能体用量明细。返回 JSON，明细在 rows 数组里。";

    private final IPlatformDigestService digestService;

    /**
     * @param digestService 日报统计服务（唯一口径）
     */
    public PlatformDailyDrillTool(IPlatformDigestService digestService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.digestService = digestService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = AiToolSupport.input(param);
        String block = AiToolSupport.stringValue(input.get("block")).trim().toLowerCase();
        if (!BLOCKS.contains(block)) {
            return AiToolSupport.fail(param, TOOL_NAME,
                "block 必填，取值 permission / login / task / usage，收到："
                    + AiToolSupport.display(input.get("block")));
        }
        Object rawDate = input.get("date");
        LocalDate date = AiToolSupport.parseDate(rawDate);
        if (rawDate != null && date == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "date 参数格式应为 YYYY-MM-DD，收到：" + rawDate);
        }
        LocalDate effectiveDate = date == null ? LocalDate.now().minusDays(1) : date;
        int limit = AiToolSupport.parseLimit(input.get("limit"),
            IPlatformDigestService.DEFAULT_DRILL_LIMIT, IPlatformDigestService.MAX_DRILL_LIMIT);
        List<Map<String, Object>> rows = digestService.drill(block, effectiveDate, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", effectiveDate.toString());
        result.put("block", block);
        result.put("limit", limit);
        result.put("rows", rows);
        return AiToolSupport.ok(param, TOOL_NAME, JsonUtils.toJsonString(result));
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("date", Map.of(
            "type", "string",
            "description", "统计日期（YYYY-MM-DD）；不传则取昨天"));
        props.put("block", Map.of(
            "type", "string",
            "enum", List.of(IPlatformDigestService.BLOCK_PERMISSION, IPlatformDigestService.BLOCK_LOGIN,
                IPlatformDigestService.BLOCK_TASK, IPlatformDigestService.BLOCK_USAGE),
            "description", "要下钻的块名"));
        props.put("limit", Map.of(
            "type", "integer",
            "description", "返回条数上限（默认 " + IPlatformDigestService.DEFAULT_DRILL_LIMIT
                + "，最多 " + IPlatformDigestService.MAX_DRILL_LIMIT + "）"));
        schema.put("properties", props);
        schema.put("required", List.of("block"));
        return schema;
    }
}
