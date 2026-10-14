package org.dromara.ai.core.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.common.core.utils.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP 客户端的结果守卫装饰器（安全合规 P3 ③ 补口：MCP 侧）。
 *
 * <p>业务工具由 {@code ToolResultGuardTool} 在装配期统一包装，其「喂给模型的那一份」已脱敏；
 * 而外部 MCP 工具由框架 {@code McpClientManager} 直接构造 {@code McpTool} 并注册，平台拿不到
 * 包装点，故 <b>第三方 MCP 工具回参里的 PII / 命中词会原样喂给模型</b>（框架自声明工具、回参内容
 * 不受平台控制）。本类在 MCP 客户端层拦截：工具调用回参在交给框架转换之前即完成脱敏。</p>
 *
 * <p><b>为什么装饰客户端而不是包工具</b>：MCP 工具实例由框架在注册时构造，平台无法逐个替换；
 * 而客户端是本平台自己创建并注册的（见 {@code AiMcpClientServiceImpl#openClients}），
 * 装饰它即可覆盖该 Server 的全部工具，且<b>对模型与权限引擎完全透明</b>——工具清单
 * （name / description / inputSchema / readOnly）由 {@link #listTools()} 原样透传，权限判定由框架
 * 在读工具定义时完成，不经过本类。</p>
 *
 * <p><b>与 {@link NormalizingMcpClientWrapper} 的组合顺序</b>：本类必须包在<b>最外层</b>
 * （装配期套在归一化装饰之外）——归一化只改名字，本类只改回参，互不干扰；顺序错也不致命，
 * 但外层更直观。</p>
 *
 * <p><b>处置口径</b>（与业务工具的 {@code ToolResultGuardTool} 一致）：沿用「只脱敏不阻断」——
 * 命中内容审核的 {@code MASK} 与 PII 一律以脱敏文本回填；内容审核配置为 {@code BLOCK} 时以统一拒答
 * 文案替代原文（避免把命中内容送给模型）。**失败结果（{@code isError=true}）原样放行**：其文本是上游
 * 工具的校验提示，改写会干扰模型对失败原因的判断。非文本内容块（图片 / 音频 / 二进制资源）不做处理。</p>
 *
 * <p><b>开关</b>：复用现有 {@code toolResultMask}（默认开）——关掉即 MCP 回参完全不过审，与业务工具口径一致。</p>
 *
 * <p><b>留痕口径</b>：本类发生在框架回填之前，脱敏后的文本不再命中，
 * 故 MCP 回参命中<b>不在 {@code ai_safety_event} 留痕</b>（与业务工具经 {@code ToolResultGuardTool}
 * 后的情形相同，见 docs/14 §90「已知限制」）；「下发前端」那一份仍由
 * {@code AiStreamEventBridge#onToolResultEnd} 过守卫。</p>
 *
 * @author BizBuddy
 */
public class GuardingMcpClientWrapper extends McpClientWrapper {

    private final McpClientWrapper delegate;

    /**
     * 安全守卫（内容审核 + PII 脱敏）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * @param delegate    被装饰的 MCP 客户端（归一化装饰之后；本类不接管其生命周期）
     * @param safetyGuard 安全守卫
     */
    public GuardingMcpClientWrapper(McpClientWrapper delegate, AiSafetyGuard safetyGuard) {
        super(delegate.getName());
        this.delegate = delegate;
        this.safetyGuard = safetyGuard;
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        // 工具定义原样透传：name / description / inputSchema / readOnly 不得被改写（权限与模型可见面）
        return delegate.listTools();
    }

    @Override
    public McpSchema.Tool getCachedTool(String name) {
        return delegate.getCachedTool(name);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        return delegate.callTool(name, arguments).map(this::guard);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments,
                                                   Map<String, Object> meta) {
        return delegate.callTool(name, arguments, meta).map(this::guard);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 改写回参：逐块脱敏文本内容。
     *
     * @param result 原回参
     * @return 脱敏后的回参（无变化时返回原对象）
     */
    private McpSchema.CallToolResult guard(McpSchema.CallToolResult result) {
        if (result == null || Boolean.TRUE.equals(result.isError()) || !safetyGuard.anyActive()) {
            return result;
        }
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return result;
        }
        boolean changed = false;
        List<McpSchema.Content> guarded = new ArrayList<>(content.size());
        for (McpSchema.Content item : content) {
            McpSchema.Content next = guardContent(item);
            changed |= next != item;
            guarded.add(next);
        }
        if (!changed) {
            return result;
        }
        // 保留 isError / structuredContent / meta（structuredContent 框架不喂模型，但改写时不应丢弃）。
        // MCP SDK 的 builder 对 content / isError 强制非空、对 structuredContent 拒绝 null，故按需设置。
        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
            .content(guarded)
            .isError(result.isError() != null ? result.isError() : Boolean.FALSE);
        if (result.structuredContent() != null) {
            builder.structuredContent(result.structuredContent());
        }
        if (result.meta() != null) {
            builder.meta(result.meta());
        }
        return builder.build();
    }

    /**
     * 单块脱敏：只处理文本块，其它内容块（图片 / 音频 / 二进制资源）原样返回。
     *
     * @param item 内容块
     * @return 脱敏后的内容块（无变化时返回原对象）
     */
    private McpSchema.Content guardContent(McpSchema.Content item) {
        if (!(item instanceof McpSchema.TextContent textContent)) {
            return item;
        }
        String text = textContent.text();
        if (StringUtils.isEmpty(text)) {
            return item;
        }
        SafetyDecision decision = safetyGuard.guard(text, SafetyScene.TOOL_RESULT);
        if (!decision.hit()) {
            return item;
        }
        String guarded = decision.blocked() ? AiSafetyGuard.REFUSAL_TEXT : decision.text();
        return new McpSchema.TextContent(textContent.annotations(), guarded, textContent.meta());
    }

}
