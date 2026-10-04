package org.dromara.ai.domain.dto;

import java.util.List;
import java.util.Map;

/**
 * 智能体装配所需的 MCP 客户端解析结果（REQ-AI-16）。
 *
 * <p>自 2026-09-30 起 MCP 工具改为**按场景包接入**：只有会话所选场景包内勾选的 MCP 工具才会装配，
 * 未选包的会话（含定时任务 / 渠道接入等无人值守链路）不接入任何 MCP 工具。</p>
 *
 * @param serverNames 本次需要接入的 Server 名（按连接配置主键升序，同时决定工具名的分配顺序）
 * @param toolNames   归一化后**实际会注册**的工具名（必须显式放进 {@code ToolsConfig.allow}，
 *                    否则会被框架 {@code ToolFilter} 裁掉）
 * @param aliases     发生了改名的工具：{@code serverName → (远端原始名 → 归一化名)}；
 *                    无改名的 Server 不出现在 Map 中
 * @param toolDecisions 包内授权决策：{@code 归一化工具名 → 决策（0允许1需确认2禁止）}；
 *                      装配时据此生成 allow / ask / deny 规则
 * @param signature   装配指纹片段（连接配置 + 发现到的工具 + 改名映射 + 包内决策；不含请求头等凭据）
 * @author BizBuddy
 */
public record McpClientAssembly(List<String> serverNames, List<String> toolNames,
                                Map<String, Map<String, String>> aliases,
                                Map<String, String> toolDecisions, String signature) {

    /**
     * 空结果（该会话未选场景包，或包内未勾选任何 MCP 工具）。
     *
     * @return 空装配结果
     */
    public static McpClientAssembly empty() {
        return new McpClientAssembly(List.of(), List.of(), Map.of(), Map.of(), "");
    }

    /**
     * 取指定 Server 的改名映射。
     *
     * @param serverName Server 名
     * @return 远端原始名 → 归一化名；该 Server 无改名时返回空 Map
     */
    public Map<String, String> aliasesOf(String serverName) {
        return aliases.getOrDefault(serverName, Map.of());
    }

    /**
     * 取指定（归一化）工具名的包内授权决策。
     *
     * @param toolName 归一化工具名
     * @return 决策值；未配置时返回 null（由装配层按默认档位兜底）
     */
    public String decisionOf(String toolName) {
        return toolDecisions.get(toolName);
    }

}