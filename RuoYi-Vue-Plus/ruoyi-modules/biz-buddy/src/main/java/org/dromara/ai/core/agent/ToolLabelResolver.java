package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.service.IAiToolService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 工具中文显示名与说明的解析器（供 HITL 确认卡片展示）。
 *
 * <p>确认卡片的数据源（{@link AiConfirmPendingStore.PendingToolCall}）只携带工具编码，
 * 前端直接渲染编码（如 {@code create_notice}）用户看不懂，故在下发前按 {@code ai_tool}
 * 的 {@code tool_code} 补出中文名（{@code tool_name}）与说明（{@code description}）。</p>
 *
 * <p><b>MCP 工具回退</b>：MCP 出口的工具名是 {@code mcp_&lt;hash&gt;} 别名，{@code ai_tool}
 * 中不会有对应行，此时 {@link #labelOf} 原样返回编码本身，{@link #descriptionOf} 返回空串——
 * 卡片不会因此缺字段。</p>
 *
 * <p><b>缓存</b>：工具名为管理端配置、极少变动，且每次确认都查库无谓。故用进程内
 * {@code ConcurrentHashMap} 做「固定 TTL」缓存（命中与未命中都缓存，避免 MCP 别名反复查库），
 * 过期后回源重查。选择固定 TTL 而非依赖 {@code update_time}：一是无需改动查询口径，
 * 二是管理端改名的生效延迟几分钟可接受，换来的是零额外查询与实现简单。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolLabelResolver {

    /**
     * 缓存有效期：超过则回源重查（工具改名后的生效延迟即该时长）
     */
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(5).toNanos();

    /**
     * 业务工具注册 Service：按编码查询工具元数据
     */
    private final IAiToolService toolService;

    /**
     * 进程内 TTL 缓存：key 为工具编码，value 为解析结果
     */
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 取工具中文显示名。
     *
     * @param toolCode 工具编码（即 {@code AgentTool} 的 name）
     * @return 中文名；未在 {@code ai_tool} 登记（如 MCP 别名）时原样返回编码
     */
    public String labelOf(String toolCode) {
        return resolve(toolCode).label();
    }

    /**
     * 取工具说明。
     *
     * @param toolCode 工具编码
     * @return 注册说明；未登记时返回空串
     */
    public String descriptionOf(String toolCode) {
        return resolve(toolCode).description();
    }

    /**
     * 解析工具编码对应的中文名与说明（带 TTL 缓存）。
     *
     * @param toolCode 工具编码
     * @return 解析结果；编码为空或未登记时走回退值
     */
    private ToolLabel resolve(String toolCode) {
        if (StringUtils.isBlank(toolCode)) {
            return new ToolLabel("", "");
        }
        long now = System.nanoTime();
        CacheEntry cached = cache.get(toolCode);
        if (cached != null && cached.expireAtNanos() > now) {
            return cached.label();
        }
        ToolLabel label = queryLabel(toolCode);
        cache.put(toolCode, new CacheEntry(label, now + CACHE_TTL_NANOS));
        return label;
    }

    /**
     * 回源查询：未登记（返回 null）或中文名为空时回退为编码本身。
     *
     * @param toolCode 工具编码
     * @return 解析结果
     */
    private ToolLabel queryLabel(String toolCode) {
        AiTool tool = toolService.queryEntity(toolCode);
        if (tool == null) {
            // MCP 工具（mcp_<hash>）等未登记编码：中文名回退为编码，说明为空
            return new ToolLabel(toolCode, "");
        }
        String label = StringUtils.isBlank(tool.getToolName()) ? toolCode : tool.getToolName();
        String description = tool.getDescription() == null ? "" : tool.getDescription();
        return new ToolLabel(label, description);
    }

    /**
     * 解析结果。
     *
     * @param label       中文显示名（未登记时等于工具编码）
     * @param description 注册说明（未登记时为空串）
     */
    private record ToolLabel(String label, String description) {
    }

    /**
     * 缓存条目。
     *
     * @param label         解析结果
     * @param expireAtNanos 过期时刻（{@link System#nanoTime()} 基准）
     */
    private record CacheEntry(ToolLabel label, long expireAtNanos) {
    }

}
