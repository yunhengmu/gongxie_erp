package org.dromara.ai.core.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP 客户端的「工具名归一化」装饰器（REQ-AI-16）。
 *
 * <p>框架注册 MCP 工具时直接使用远端自声明的 {@code McpSchema.Tool.name()}
 * （见 {@code McpClientManager} 的注册回调），且 {@code McpTool} 调用时又用**同一个名字**回查
 * {@code McpClientWrapper.callTool(name, ...)}。因此只要让 {@link #listTools()} 返回改名后的工具，
 * 调用侧便会自动以归一化名发起，本类再把名字还原为远端原始名即可，无需改动框架。</p>
 *
 * <p>注册进工具箱的客户端由本类包装：{@code initialize / close / isInitialized} 一律透传，
 * 只有工具清单与调用参数上的工具名会被改写（未登记改名的工具原样透传）。</p>
 *
 * @author BizBuddy
 */
public class NormalizingMcpClientWrapper extends McpClientWrapper {

    private final McpClientWrapper delegate;

    /**
     * 远端原始名 → 归一化名
     */
    private final Map<String, String> aliasByOriginal;

    /**
     * 归一化名 → 远端原始名
     */
    private final Map<String, String> originalByAlias;

    /**
     * @param delegate        真实的 MCP 客户端（本类不接管其生命周期）
     * @param aliasByOriginal 该 Server 的改名映射（远端原始名 → 归一化名，非空）
     */
    public NormalizingMcpClientWrapper(McpClientWrapper delegate, Map<String, String> aliasByOriginal) {
        super(delegate.getName());
        this.delegate = delegate;
        this.aliasByOriginal = Map.copyOf(aliasByOriginal);
        Map<String, String> reverse = new LinkedHashMap<>();
        aliasByOriginal.forEach((original, alias) -> reverse.put(alias, original));
        this.originalByAlias = Map.copyOf(reverse);
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
        return delegate.listTools().map(this::renameTools);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        return delegate.callTool(toRemoteName(name), arguments);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments,
                                                   Map<String, Object> meta) {
        return delegate.callTool(toRemoteName(name), arguments, meta);
    }

    @Override
    public McpSchema.Tool getCachedTool(String name) {
        String remote = originalByAlias.get(name);
        return remote == null ? delegate.getCachedTool(name) : delegate.getCachedTool(remote);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 把远端声明的工具改名为归一化名（未登记改名的工具原样返回）。
     *
     * @param tools 远端工具清单
     * @return 改名后的工具清单
     */
    private List<McpSchema.Tool> renameTools(List<McpSchema.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<McpSchema.Tool> renamed = new ArrayList<>(tools.size());
        for (McpSchema.Tool tool : tools) {
            String alias = aliasByOriginal.get(tool.name());
            if (alias == null || alias.equals(tool.name())) {
                renamed.add(tool);
                continue;
            }
            McpSchema.Tool replaced = McpSchema.Tool.builder()
                .name(alias)
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .outputSchema(tool.outputSchema())
                .annotations(tool.annotations())
                .meta(tool.meta())
                .build();
            // 基类缓存按工具名索引：改名后需以新名登记，保持 getCachedTool 的语义一致
            cachedTools.put(alias, replaced);
            renamed.add(replaced);
        }
        return renamed;
    }

    /**
     * 把归一化名还原为远端原始名。
     *
     * @param name 归一化名
     * @return 远端原始名；未登记改名的名字原样返回
     */
    private String toRemoteName(String name) {
        return name == null ? null : originalByAlias.getOrDefault(name, name);
    }

}