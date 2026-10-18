package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.domain.vo.PlatformDigestVo;
import org.dromara.ai.service.IPlatformDigestService;
import org.dromara.common.json.utils.JsonUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台运行日报 —— **五块汇总**工具（只读）。
 *
 * <p>一次调用拿到全部数据（用量与配额、定时任务成败、待办积压、权限决策、登录与异动），
 * 口径全部在 {@link IPlatformDigestService} 里算好 —— 工具只负责取数，不做二次加工，
 * 避免与投递器（落库 / 推送）出现口径漂移。</p>
 *
 * <p>返回 JSON；**某块为 {@code null} 表示当日该块无数据**，与「有数据且为 0」是两件事，
 * 技能里明确要求如实写「无数据」。</p>
 *
 * @author BizBuddy
 */
public class PlatformDailyDigestTool extends ToolBase {

    /**
     * 工具编码（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "platform_daily_digest";

    private static final String DESCRIPTION =
        "汇总某一天（默认昨天）的平台运行五块数据：AI 用量与配额、定时任务成败、工作流待办积压、"
            + "权限决策计数、登录与「新 IP」异动。返回 JSON；**某块为 null 表示当日该块无数据**"
            + "（不要当成 0）。生成运行日报时**只调本工具一次**即可拿到全部数据；需要明细再用 "
            + "platform_daily_drill 下钻。";

    private final IPlatformDigestService digestService;

    /**
     * @param digestService 日报统计服务（唯一口径）
     */
    public PlatformDailyDigestTool(IPlatformDigestService digestService) {
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
        PlatformDigestVo digest = digestService.digest(date);
        return AiToolSupport.ok(param, TOOL_NAME, JsonUtils.toJsonString(digest));
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
        schema.put("properties", props);
        return schema;
    }
}
