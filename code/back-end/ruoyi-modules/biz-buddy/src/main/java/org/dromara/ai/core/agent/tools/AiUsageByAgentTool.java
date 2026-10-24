package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.service.IPlatformDigestService;
import org.dromara.common.json.utils.JsonUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台运行日报 —— **按智能体用量**工具（只读）。
 *
 * <p>回答「昨天谁用得最多」「哪个智能体最费 Token」这类问题：按智能体拆解当日 Token、
 * 请求数与工具调用次数（智能体名称取自入口 / 专家两表）。</p>
 *
 * @author BizBuddy
 */
public class AiUsageByAgentTool extends ToolBase {

    /**
     * 工具编码（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "ai_usage_by_agent";

    private static final String DESCRIPTION =
        "按智能体拆解某一天（默认昨天）的 AI 用量：Token / 请求数 / 工具调用次数，按 Token 降序。"
            + "用于回答「昨天谁用得最多」「哪个智能体最费 Token」等问题。";

    private final IPlatformDigestService digestService;

    /**
     * @param digestService 日报统计服务（唯一口径）
     */
    public AiUsageByAgentTool(IPlatformDigestService digestService) {
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
        Object rawDate = input.get("date");
        LocalDate date = AiToolSupport.parseDate(rawDate);
        if (rawDate != null && date == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "date 参数格式应为 YYYY-MM-DD，收到：" + rawDate);
        }
        int limit = AiToolSupport.parseLimit(input.get("limit"),
            IPlatformDigestService.DEFAULT_DRILL_LIMIT, IPlatformDigestService.MAX_DRILL_LIMIT);
        LocalDate effectiveDate = date == null ? LocalDate.now().minusDays(1) : date;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", effectiveDate.toString());
        result.put("rows", digestService.usageByAgent(effectiveDate, limit));
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
        props.put("limit", Map.of(
            "type", "integer",
            "description", "返回条数上限（默认 " + IPlatformDigestService.DEFAULT_DRILL_LIMIT
                + "，最多 " + IPlatformDigestService.MAX_DRILL_LIMIT + "）"));
        schema.put("properties", props);
        return schema;
    }
}
