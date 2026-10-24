package org.dromara.demo.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * MCP Server 演示工具（程序化注册）。
 * <p>
 * 原 {@code McpDemoServerTool} 使用 spring-ai 的 {@code @McpTool}/{@code @McpResource} 注解，
 * 已随 spring-ai 一并移除；现改为向容器注册 {@link McpStatelessServerFeatures.SyncToolSpecification}
 * 与 {@link McpStatelessServerFeatures.SyncResourceSpecification} Bean，
 * 由 {@code McpServerAutoConfiguration} 统一挂到 `/mcp` 出口，暴露能力与迁移前保持一致：
 * <ul>
 *     <li>`demo_get_data`：根据 id 查询一条演示数据</li>
 *     <li>`demo_list_data`：按 limit 查询演示数据列表</li>
 *     <li>`demo://summary`：读取演示模块摘要资源</li>
 * </ul>
 * 真实业务中不要在 MCP 工具里直接访问 Mapper，应继续调用业务 Service，
 * 并保留权限、租户、脱敏、审计和幂等规则。
 *
 * @author Lion Li
 */
@Configuration(proxyBeanMethods = false)
public class McpDemoServerTools {

    /**
     * 查询一条演示数据。
     */
    @Bean
    public McpStatelessServerFeatures.SyncToolSpecification demoGetDataTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("demo_get_data")
            .description("根据业务主键查询演示数据")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("id", Map.of("type", "string", "description", "业务主键")),
                List.of("id"), true, null, null))
            .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((context, request) -> {
                String id = String.valueOf(request.arguments().get("id"));
                return textResult(demoDataJson(id, "演示数据-" + id, 100));
            })
            .build();
    }

    /**
     * 查询演示数据列表。
     */
    @Bean
    public McpStatelessServerFeatures.SyncToolSpecification demoListDataTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("demo_list_data")
            .description("查询演示数据列表")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("limit", Map.of("type", "integer", "description", "返回条数")),
                List.of(), true, null, null))
            .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((context, request) -> {
                Object rawLimit = request.arguments() == null ? null : request.arguments().get("limit");
                int limit = rawLimit instanceof Number number ? number.intValue() : 3;
                int size = limit < 1 ? 3 : Math.min(limit, 20);
                String items = IntStream.rangeClosed(1, size)
                    .mapToObj(index -> demoDataJson(String.valueOf(index), "演示数据-" + index, index * 10))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
                return textResult("[" + items + "]");
            })
            .build();
    }

    /**
     * 读取演示资源。
     */
    @Bean
    public McpStatelessServerFeatures.SyncResourceSpecification demoSummaryResource() {
        McpSchema.Resource resource = McpSchema.Resource.builder()
            .uri("demo://summary")
            .name("demo_summary")
            .description("演示模块摘要资源")
            .mimeType("text/plain")
            .build();
        return new McpStatelessServerFeatures.SyncResourceSpecification(resource,
            (context, request) -> new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents("demo://summary", "text/plain", "ruoyi-demo MCP resource summary"))));
    }

    /**
     * 组装成功返回。
     *
     * @param text 文本内容
     * @return 工具调用结果
     */
    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .isError(false)
            .build();
    }

    /**
     * 生成演示数据 JSON（手工拼装，避免引入额外序列化依赖）。
     *
     * @param id     主键
     * @param name   名称
     * @param amount 数量
     * @return JSON 文本
     */
    private static String demoDataJson(String id, String name, int amount) {
        return "{\"id\":\"" + escape(id) + "\",\"name\":\"" + escape(name)
            + "\",\"source\":\"ruoyi-demo\",\"amount\":" + amount
            + ",\"syncTime\":\"" + LocalDateTime.now() + "\"}";
    }

    /**
     * 最小 JSON 字符串转义。
     *
     * @param value 原始值
     * @return 转义后的值
     */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}