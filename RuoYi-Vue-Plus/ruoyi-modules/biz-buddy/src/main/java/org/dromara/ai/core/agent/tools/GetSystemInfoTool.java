package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 获取当前系统信息工具（只读、无参数）。
 *
 * <p>返回运行主机的操作系统名称 / 版本 / 架构、处理器逻辑核数、物理内存与 JVM 堆内存用量、
 * Java 版本、主机名与时区。用作 <b>MCP 服务端 / 客户端联动演示</b>：该工具在 {@code ai_tool} 中
 * 标记 {@code mcp_exposed=1}，由平台 {@code /mcp} 出口对外暴露；再由「平台系统信息」MCP 客户端
 * （见 {@code McpSelfBootstrap}）连回同一出口并纳入白名单。仅当对话时经会话资源选择器勾选后才会装配。</p>
 *
 * @author BizBuddy
 */
public class GetSystemInfoTool extends ToolBase {

    /**
     * 工具编码（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_system_info";

    /**
     * 工具描述（模型据此判断何时调用，同时作为出口对外的能力说明）
     */
    private static final String DESCRIPTION =
        "获取当前运行主机的系统信息：操作系统名称与版本、系统架构、处理器逻辑核数、"
            + "物理内存与 JVM 堆内存用量、Java 版本、主机名与时区。用于排查运行环境，"
            + "或回答「这台机器配置如何」类问题。";

    public GetSystemInfoTool() {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? TOOL_NAME : use.getName();
        return Mono.just(ToolResultBlock.text(render()).withIdAndName(id, name));
    }

    /**
     * 渲染系统信息文本。
     *
     * @return 多行文本
     */
    private static String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("操作系统：").append(System.getProperty("os.name", "未知"))
            .append(' ').append(System.getProperty("os.version", ""))
            .append("（").append(System.getProperty("os.arch", "")).append("）\n");
        sb.append("处理器逻辑核数：").append(Runtime.getRuntime().availableProcessors()).append('\n');
        appendPhysicalMemory(sb);
        Runtime runtime = Runtime.getRuntime();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        sb.append("JVM 堆内存：已用 ").append(toMb(usedHeap))
            .append(" / 上限 ").append(runtime.maxMemory() == Long.MAX_VALUE ? "无上限" : toMb(runtime.maxMemory()))
            .append('\n');
        sb.append("Java 版本：").append(System.getProperty("java.version", "")).append('\n');
        sb.append("主机名：").append(hostName()).append('\n');
        sb.append("时区：").append(ZoneId.systemDefault().getId());
        return sb.toString();
    }

    /**
     * 追加物理内存用量（{@code com.sun.management.OperatingSystemMXBean} 不可用时跳过）。
     *
     * @param sb 目标缓冲
     */
    private static void appendPhysicalMemory(StringBuilder sb) {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            long total = extended.getTotalMemorySize();
            long free = extended.getFreeMemorySize();
            if (total > 0) {
                sb.append("物理内存：已用 ").append(toMb(total - free))
                    .append(" / 总量 ").append(toMb(total)).append('\n');
            }
        }
    }

    /**
     * 取主机名（失败时回退环境变量）。
     *
     * @return 主机名或「未知」
     */
    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("HOSTNAME");
            return env == null || env.isBlank() ? "未知" : env;
        }
    }

    /**
     * 字节转 MB 文本。
     *
     * @param bytes 字节数
     * @return 如 {@code 1024 MB}
     */
    private static String toMb(long bytes) {
        return (bytes / 1024 / 1024) + " MB";
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