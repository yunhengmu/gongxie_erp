package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体装配快照工厂：构造执行期装配快照（REQ-AI-28 链路追踪）。
 *
 * <p>从 {@link AgentRegistry} 抽出。快照与指纹同源取数，由注册表在同一次装配中用同一批入参调用，
 * 不各自查库；本类不持有装配缓存。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AgentSnapshotFactory {

    private final AiPermissionModeStore permissionModeStore;

    private final AgentModelFactory modelFactory;

    /**
     * 构造执行期装配快照（REQ-AI-28 链路追踪）。
     *
     * <p>模型版本取该次装配实际构造的模型配置（含超时与重试），上下文清单取实际生效的
     * 工具、知识库、技能、子智能体、外部 MCP 与各运行期策略。快照随装配结果缓存，
     * 因此链路记录的是**执行当时**的事实，事后改配置不会改写历史。</p>
     *
     * <p><b>与装配指纹的字段差异是刻意的</b>：本方法的策略块是**展示子集**（逐字段对应前端
     * 「链路追踪 → 上下文清单」的白名单常量 {@code CONTEXT_OBJECTS}），而
     * {@code compactionSignature} / {@code planSignature} / {@code memorySignature} /
     * {@code workspaceSignature} 是**变更检测超集**（只要影响装配结果
     * 就必须进指纹触发重建）。因此改其一之前请先判断：若新字段影响装配，加进指纹即可（展示可不动）；
     * 若还需展示，须**同时**改本方法对应块与前端白名单。</p>
     *
     * @param definition     智能体定义
     * @param modelConfig    主对话模型配置
     * @param registeredCodes 实际注册成功的工具编码
     * @param kbIds          绑定的知识库主键
     * @param skillNames     生效技能过滤键
     * @param subagents      绑定的启用中子智能体
     * @param bindings       工具绑定（含授权决策）
     * @param trusts         「记住该工具」信任决策（运行期人工确认沉淀）
     * @param compactionPolicy 上下文压缩策略
     * @param planPolicy     计划模式与任务清单配置
     * @param memoryPolicy   长期记忆配置
     * @param workspacePolicy 工作区上下文配置
     * @param mcpAssembly    外部 MCP 连接装配结果
     * @param maxIters       单轮最大迭代次数
     * @return 装配快照
     */
    AgentSnapshot buildSnapshot(AiAgent definition, AiModelConfig modelConfig,
                                List<String> registeredCodes, List<Long> kbIds, List<String> skillNames,
                                List<AiSubagent> subagents, List<AiAgentTool> bindings,
                                AiCompactionConfigBo compactionPolicy, AiPlanConfigBo planPolicy,
                                AiMemoryConfigBo memoryPolicy,
                                AiWorkspaceConfigBo workspacePolicy, McpClientAssembly mcpAssembly,
                                int maxIters) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tools", registeredCodes);
        context.put("kbIds", kbIds);
        context.put("skills", skillNames);
        context.put("subagents", subagents.stream().map(AiSubagent::getName).toList());
        context.put("mcpTools", mcpAssembly.toolNames());
        context.put("permissionMode", permissionModeStore.resolve().name());
        Map<String, String> decisions = new LinkedHashMap<>();
        for (AiAgentTool bind : bindings) {
            decisions.put(bind.getToolCode(), bind.getDecision());
        }
        context.put("toolDecisions", decisions);
        context.put("maxIters", maxIters);
        // 注意：此处一律用 LinkedHashMap.put（允许 null），不可用 Map.of（null 值会抛 NPE）
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("timeoutSeconds", modelConfig.getTimeoutSeconds());
        model.put("maxRetries", modelConfig.getMaxRetries());
        model.put("baseUrl", modelConfig.getBaseUrl());
        context.put("model", model);
        Map<String, Object> compaction = new LinkedHashMap<>();
        compaction.put("enabled", compactionPolicy.isEnabled());
        compaction.put("triggerMessages", compactionPolicy.getTriggerMessages());
        compaction.put("triggerTokens", compactionPolicy.getTriggerTokens());
        compaction.put("keepMessages", compactionPolicy.getKeepMessages());
        compaction.put("flushBeforeCompact", compactionPolicy.isFlushBeforeCompact());
        compaction.put("offloadBeforeCompact", compactionPolicy.isOffloadBeforeCompact());
        compaction.put("toolResultEviction", compactionPolicy.isToolResultEvictionEnabled());
        compaction.put("toolResultEvictionMaxChars", compactionPolicy.getToolResultEvictionMaxChars());
        context.put("compaction", compaction);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("enabled", planPolicy.isEnabled());
        plan.put("planFileDirectory", planPolicy.getPlanFileDirectory());
        plan.put("taskListEnabled", planPolicy.isTaskListEnabled());
        context.put("plan", plan);
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("enabled", memoryPolicy.isEnabled());
        memory.put("maxContextTokens", memoryPolicy.getMaxContextTokens());
        memory.put("consolidationMaxTokens", memoryPolicy.getConsolidationMaxTokens());
        memory.put("dailyFileRetentionDays", memoryPolicy.getDailyFileRetentionDays());
        memory.put("sessionRetentionDays", memoryPolicy.getSessionRetentionDays());
        context.put("memory", memory);
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("enabled", workspacePolicy.isEnabled());
        workspace.put("additionalContextFiles", workspacePolicy.getAdditionalContextFiles());
        context.put("workspace", workspace);
        return new AgentSnapshot(definition.getModelId(), modelConfig.getModelCode(), modelConfig.getProvider(),
            modelFactory.slotModelCode(definition.getSummaryModelId()),
            modelFactory.slotModelCode(definition.getMemoryModelId()),
            modelFactory.slotModelCode(definition.getSubagentModelId()), context);
    }

}
