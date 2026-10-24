package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体装配指纹计算器：把定义与各运行期策略归一为一个字符串，用于判断是否需要重新装配。
 *
 * <p>从 {@link AgentRegistry} 抽出。全部方法为无状态纯函数，只读取模型配置表（用于模型槽位指纹），
 * 不持有装配缓存。注意：**拼接的字段顺序与分隔符属于指纹契约**，任何改动都会让全部缓存失效。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AgentFingerprintCalculator {

    private final AiModelConfigMapper modelConfigMapper;

    /**
     * 生成定义指纹，用于判断是否需要重新装配。
     *
     * @param definition   智能体定义
     * @param enabledTools 启用中的业务工具实体（含类型与更新时间）
     * @param bindings     工具绑定（含授权决策）
     * @param modelSignature 模型配置指纹片段（主对话模型 + 两个可选槽位模型）
     * @param compactionPolicy 上下文压缩策略
     * @param planPolicy   计划模式与任务清单配置
     * @param memoryPolicy 长期记忆配置
     * @param workspacePolicy 工作区上下文配置
     * @param skillNames    绑定且启用中的技能标识（技能来源仅本项目数据库，无来源签名需纳入）
     * @param mcpAssembly   外部 MCP 连接装配结果（REQ-AI-16；已按场景包收敛）
     * @param subagents     绑定的启用中子智能体（REQ-AI-04）
     * @param packSignature 场景包装配指纹片段（包本体 + 三类绑定；未选包时为空串）
     * @return 指纹字符串
     */
    String buildFingerprint(AiAgent definition, List<AiTool> enabledTools, List<AiAgentTool> bindings,
                            String modelSignature, AiCompactionConfigBo compactionPolicy,
                            AiPlanConfigBo planPolicy, AiMemoryConfigBo memoryPolicy,
                            AiWorkspaceConfigBo workspacePolicy,
                            List<String> skillNames,
                            McpClientAssembly mcpAssembly,
                            List<AiSubagent> subagents,
                            String packSignature) {
        StringBuilder toolPart = new StringBuilder();
        for (AiTool tool : enabledTools) {
            toolPart.append(tool.getToolCode()).append(':')
                .append(tool.getToolType()).append(':')
                .append(tool.getImplName()).append(':')
                .append(tool.getUpdateTime()).append(';');
        }
        StringBuilder decisionPart = new StringBuilder();
        for (AiAgentTool bind : bindings) {
            decisionPart.append(bind.getToolCode()).append('=').append(bind.getDecision()).append(';');
        }
        return String.valueOf(definition.getUpdateTime())
            + "|" + definition.getStatus()
            + "|" + definition.getModelId()
            + "|" + definition.getSummaryModelId()
            + "|" + definition.getMemoryModelId()
            + "|" + definition.getAgentCode()
            + "|" + toolPart
            + "|" + decisionPart
            + "|" + modelSignature
            + "|" + compactionSignature(compactionPolicy)
            + "|" + planSignature(planPolicy)
            + "|" + memorySignature(memoryPolicy)
            + "|" + workspaceSignature(workspacePolicy)
            // 技能绑定集合（含启用状态筛选结果）：技能正文变更无需重建（框架每次调用按内容哈希重载），
            // 但绑定增减或技能停用/启用会改变是否注册技能工具与注入的技能清单，必须触发重建
            + "|skills=" + String.join(",", skillNames)
            // 子智能体绑定集合（含启用状态筛选结果）：声明字段或绑定增减都会改变装配出的子智能体，
            // 必须触发重建（REQ-AI-04）
            + "|subagents=" + subagentSignature(subagents)
            // 外部 MCP 连接（按场景包接入）：连接配置、发现到的工具名与包内决策变化时重建
            + "|mcp=" + mcpAssembly.signature()
            // 场景包（装配单元为「智能体 × 场景包」）：包本体或三类绑定变化时重建
            + "|pack=" + packSignature;
    }

    /**
     * 生成模型配置指纹片段。
     *
     * <p>模型配置（服务地址 / 密钥 / 超时 / 重试 / 启用状态）与智能体定义分属两张表，
     * 但其任何变更都会改变装配结果（模型实例、超时重试参数）。此前仅定义侧变更才触发重建，
     * 导致「改完模型配置不生效、必须重启进程」；此处把主对话模型与两个可选槽位模型一并纳入指纹。</p>
     *
     * <p>仅取足以判定变更的字段（更新时间 + 关键属性），**不含密钥明文/密文**，避免敏感值进入比较串。</p>
     *
     * @param definition 智能体定义
     * @return 指纹片段（无任何模型时为空串）
     */
    String modelSignature(AiAgent definition) {
        StringBuilder signature = new StringBuilder();
        appendModelSignature(signature, "chat", definition.getModelId());
        appendModelSignature(signature, "summary", definition.getSummaryModelId());
        appendModelSignature(signature, "memory", definition.getMemoryModelId());
        appendModelSignature(signature, "subagent", definition.getSubagentModelId());
        return signature.toString();
    }

    /**
     * 追加单个模型配置的指纹片段；配置已被删除时记为 {@code missing}（装配阶段会给出明确报错）。
     *
     * @param signature 指纹累积器
     * @param slot      槽位名（chat / summary / memory）
     * @param modelId   模型主键
     */
    private void appendModelSignature(StringBuilder signature, String slot, Long modelId) {
        if (modelId == null) {
            return;
        }
        AiModelConfig config = modelConfigMapper.selectById(modelId);
        signature.append(slot).append('=').append(modelId).append(':');
        if (config == null) {
            signature.append("missing");
        } else {
            signature.append(config.getUpdateTime())
                .append(':').append(config.getProvider())
                .append(':').append(config.getModelCode())
                .append(':').append(config.getBaseUrl())
                .append(':').append(config.getModelType())
                .append(':').append(config.getStatus())
                .append(':').append(config.getTimeoutSeconds())
                .append(':').append(config.getMaxRetries());
        }
        signature.append(';');
    }

    /*
     * 以下五个 *Signature 方法生成的是**变更检测超集**：只要字段影响装配结果就必须纳入，否则改了配置
     * 却不重建（历史踩坑：模型配置曾未入指纹）。链路追踪展示用的是 {@code AgentSnapshotFactory#buildSnapshot}
     * 里的**展示子集**（逐字段对应前端 CONTEXT_OBJECTS 白名单），两者字段数不同属刻意设计，见其 javadoc。
     *
     * 注意：拼接的**字段顺序与分隔符属于指纹契约**，改动会让全部缓存失效并在下次访问时集中重建。
     */

    /**
     * 生成压缩策略指纹片段。
     *
     * @param policy 压缩策略
     * @return 指纹片段
     */
    private String compactionSignature(AiCompactionConfigBo policy) {
        return policy.isEnabled() + ":" + policy.getTriggerMessages() + ":" + policy.getTriggerTokens()
            + ":" + policy.getKeepMessages() + ":" + policy.isFlushBeforeCompact()
            + ":" + policy.isOffloadBeforeCompact() + ":" + policy.isToolResultEvictionEnabled()
            + ":" + policy.getToolResultEvictionMaxChars() + ":" + policy.getToolResultEvictionPreviewChars();
    }

    /**
     * 生成计划模式配置指纹片段。
     *
     * @param policy 计划模式配置
     * @return 指纹片段
     */
    private String planSignature(AiPlanConfigBo policy) {
        return policy.isEnabled() + ":" + policy.getPlanFileDirectory() + ":"
            + policy.isTaskListEnabled();
    }

    /**
     * 生成长期记忆配置指纹片段。
     *
     * @param policy 长期记忆配置
     * @return 指纹片段
     */
    private String memorySignature(AiMemoryConfigBo policy) {
        return policy.isEnabled() + ":" + policy.getPipelineMode() + ":" + policy.getMaxContextTokens() + ":"
            + policy.getInjectDeclaredMaxTokens() + ":"
            + policy.getConsolidationMaxTokens() + ":" + policy.getConsolidationMinGapMinutes() + ":"
            + policy.getDailyFileRetentionDays() + ":" + policy.getSessionRetentionDays() + ":"
            + policy.getFlushMode() + ":" + policy.getFlushThrottleMinutes();
    }

    /**
     * 生成工作区上下文配置指纹片段。
     *
     * @param policy 工作区上下文配置
     * @return 指纹片段
     */
    private String workspaceSignature(AiWorkspaceConfigBo policy) {
        List<String> files = policy.getAdditionalContextFiles() == null
            ? List.of() : policy.getAdditionalContextFiles();
        return policy.isEnabled() + ":" + String.join(",", files);
    }

    /**
     * 生成子智能体装配指纹片段。
     *
     * <p>子智能体定义的任何可影响装配的字段（标识 / 用途说明 / 提示词 / 工作区模式 / 模型 / 步数 /
     * 工具白名单 / 启用状态 / 更新时间）或绑定集合变化，都应触发重建。</p>
     *
     * @param subagents 绑定的启用中子智能体
     * @return 指纹片段
     */
    private String subagentSignature(List<AiSubagent> subagents) {
        if (subagents == null || subagents.isEmpty()) {
            return "";
        }
        StringBuilder signature = new StringBuilder();
        for (AiSubagent subagent : subagents) {
            signature.append(subagent.getId()).append(':')
                .append(subagent.getName()).append(':')
                .append(subagent.getWorkspaceMode()).append(':')
                .append(subagent.getModelId()).append(':')
                .append(subagent.getSteps()).append(':')
                .append(subagent.getToolCodes()).append(':')
                .append(subagent.getUpdateTime()).append(';');
        }
        return signature.toString();
    }

}
