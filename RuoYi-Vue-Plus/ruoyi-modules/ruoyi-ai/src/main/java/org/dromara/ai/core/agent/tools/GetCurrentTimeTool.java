package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.CurrentTimeMiddleware;
import org.dromara.ai.core.agent.CurrentTimeSupport;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 获取当前时间工具（只读、无参数）。
 *
 * <p>与 {@link CurrentTimeMiddleware} 的关系：中间件把「当前时间」**每轮**放进 system prompt
 * （模型始终知道大致现在，零调用成本）；本工具则在模型需要**精确到秒**、或要用时间做计算的
 * 时刻按需返回，并在 {@code ai_tool_call_log} 留痕。两者共用 {@link CurrentTimeSupport}
 * 的时区口径，故不存在时间互相矛盾的问题。</p>
 *
 * <p>本工具属**平台内置无害只读工具**：无需逐智能体绑定——{@code AgentRegistry} 会无条件注册它，
 * 并在「未绑定时」默认追加 ALLOW 权限规则（否则会落到全局默认「未命中即 ASK」而弹确认卡片，
 * 对"看时间"并不合理）。若某智能体显式绑定它并配置为「需确认 / 禁止」，则以该显式决策为准。</p>
 *
 * @author BizBuddy
 */
public class GetCurrentTimeTool extends ToolBase {

    /**
     * 工具编码（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_current_time";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION =
        "获取当前的日期与时间（精确到秒，含星期与时区）。当回答需要「今天」「现在」"
            + "「距今还有几天」等依赖当前时间的判断或计算时调用；不要凭猜测回答时间。";

    private final AiProperties aiProperties;

    /**
     * @param aiProperties 平台配置（提供当前时间的时区口径）
     */
    public GetCurrentTimeTool(AiProperties aiProperties) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.aiProperties = aiProperties;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? TOOL_NAME : use.getName();
        String text = "当前时间："
            + CurrentTimeSupport.render(CurrentTimeSupport.resolveZone(
                aiProperties.getChat().getCurrentTimeZone()), CurrentTimeSupport.SECOND_FORMATTER);
        return Mono.just(ToolResultBlock.text(text).withIdAndName(id, name));
    }

    /**
     * 构造参数 JSON Schema（本工具无入参）。
     *
     * @return 空对象 Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

}